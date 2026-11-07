// The page of the notebook. Every value it shows comes as a string from the snapshot of the compiled core: this script
// computes nothing and formats nothing, it only adds the labels of the page. Text reaches the page through textContent.
(function () {
  'use strict';

  // calc.js is a classic script: its exports are properties of the window.
  const calc = window;

  // How the core ends a cut text. Like the desktop, the exact value is only added behind one: 12,5 is complete as it is.
  const CUT_MARK = '…';

  const linesElement = document.getElementById('lines');
  const modeSelect = document.getElementById('display-mode');
  const messageElement = document.getElementById('message');

  let current = JSON.parse(calc.snapshot());

  function element(tag, className, text) {
    const node = document.createElement(tag);
    node.className = className;
    node.textContent = text;
    return node;
  }

  function newRow() {
    const row = document.createElement('li');
    row.className = 'line';
    const number = element('span', 'number', '');
    number.setAttribute('aria-hidden', 'true');
    const input = document.createElement('input');
    input.className = 'expression';
    input.type = 'text';
    input.autocomplete = 'off';
    input.spellcheck = false;
    input.setAttribute('autocapitalize', 'off');
    input.setAttribute('enterkeyhint', 'next');
    row.append(number, input);
    return row;
  }

  function faultyPart(line) {
    const faulty = element('div', 'faulty', '');
    if (line.errorStart < 0) {
      faulty.textContent = line.text;
      return faulty;
    }
    // Indexes count UTF-16 units on both sides: they are the ones of a JavaScript string.
    faulty.append(
      line.text.substring(0, line.errorStart),
      element('mark', '', line.text.substring(line.errorStart, line.errorEnd)),
      line.text.substring(line.errorEnd));
    return faulty;
  }

  function outputs(line) {
    const parts = [];
    if (line.result !== null) {
      parts.push(element('span', 'result', line.result));
    }
    if (line.status === 'EXACT') {
      parts.push(element('span', 'badge badge-exact', 'Exact'));
    } else if (line.status === 'APPROXIMATE') {
      parts.push(element('span', 'badge badge-approche', '≈ Approché'));
    }
    if (line.errorBound !== null) {
      parts.push(element('div', 'detail', 'Erreur maximale : ' + line.errorBound));
    } else if (line.exactFraction !== null && line.result.includes(CUT_MARK)) {
      parts.push(element('div', 'detail', 'Valeur exacte : ' + line.exactFraction));
    }
    if (line.error !== null) {
      parts.push(element('div', 'error', 'Erreur : ' + line.error), faultyPart(line));
    }
    return parts;
  }

  // A row is kept from one display to the next, so that the field being typed in keeps its focus and its caret.
  function fill(row, line) {
    const number = row.firstElementChild;
    const input = number.nextElementSibling;
    row.setAttribute('data-line', String(line.number));
    row.setAttribute('data-status', line.status);
    number.textContent = String(line.number);
    input.setAttribute('aria-label', 'Ligne ' + line.number);
    if (input.value !== line.text) {
      input.value = line.text;
    }
    while (input.nextSibling !== null) {
      input.nextSibling.remove();
    }
    row.append(...outputs(line));
  }

  // Like the desktop, the sheet always ends with a line not yet in the notebook: typing there appends it.
  function draftLine(number) {
    return {
      number: number, text: '', status: 'EMPTY', result: null, exactFraction: null, errorBound: null, error: null,
      errorStart: -1, errorEnd: -1,
    };
  }

  function render() {
    modeSelect.value = current.mode;
    const lines = current.lines.concat(draftLine(current.lines.length + 1));
    while (linesElement.children.length > lines.length) {
      linesElement.lastElementChild.remove();
    }
    lines.forEach((line, index) => {
      fill(linesElement.children[index] || linesElement.appendChild(newRow()), line);
    });
    inputOf(lines.length).placeholder = current.lines.length === 0 ? '(1/3)*3 puis Entrée' : '';
    messageElement.hidden = true;
    messageElement.textContent = '';
  }

  function inputOf(number) {
    return linesElement.children[number - 1].querySelector('input.expression');
  }

  // False when the core refuses: the notebook did not change, and the field keeps what was typed so it can be corrected.
  function apply(answer) {
    const snapshot = JSON.parse(answer);
    if (snapshot.message !== null) {
      messageElement.textContent = snapshot.message;
      messageElement.hidden = false;
      return false;
    }
    current = snapshot;
    render();
    return true;
  }

  function validate(number, text) {
    const line = current.lines[number - 1];
    if (line === undefined) {
      return text.trim() === '' || apply(calc.append(text));
    }
    return text === line.text || apply(calc.edit(number, text));
  }

  function numberOf(input) {
    return Number(input.closest('[data-line]').getAttribute('data-line'));
  }

  linesElement.addEventListener('keydown', (event) => {
    if (event.key !== 'Enter' || event.isComposing || !event.target.matches('input.expression')) {
      return;
    }
    event.preventDefault();
    const number = numberOf(event.target);
    if (validate(number, event.target.value)) {
      const next = inputOf(Math.min(number + 1, current.lines.length + 1));
      next.focus();
      next.setSelectionRange(next.value.length, next.value.length);
    }
  });

  // Leaving a line validates it too. Nothing happens when Enter has just done so: the text is already the one of the line.
  linesElement.addEventListener('focusout', (event) => {
    if (event.target.matches('input.expression')) {
      validate(numberOf(event.target), event.target.value);
    }
  });

  modeSelect.addEventListener('change', () => {
    apply(calc.setMode(modeSelect.value));
  });

  render();
  inputOf(current.lines.length + 1).focus();
}());
