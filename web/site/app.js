// The page of the notebook. Every value it shows comes as a string from the snapshot of the compiled core: this script
// computes nothing and formats nothing, it only adds the labels of the page. Text reaches the page through textContent.
// It also keeps the notebook of the visitor in the browser: the texts of the lines and the display mode, never a result.
(function () {
  'use strict';

  // calc.js is a classic script: its exports are properties of the window.
  const calc = window;

  // How the core ends a cut text. Like the desktop, the exact value is only added behind one: 12,5 is complete as it is.
  const CUT_MARK = '…';

  // The notebook kept in the browser: {"version":1,"mode":"AUTO","lines":["…"]}.
  const STORAGE_KEY = 'calc.web.notebook.v1';
  const MODES = ['AUTO', 'DECIMAL', 'FRACTION', 'SCIENTIFIC'];
  const MAX_LINES = 5000;
  const MAX_LINE_LENGTH = 16384;
  const UNREADABLE_NOTICE = 'Carnet enregistré illisible : carnet d\'exemple chargé.';
  const NOT_KEPT_NOTICE = 'Carnet non conservé : le stockage du navigateur est indisponible.';

  const EXAMPLE = [
    '# Carnet d\'exemple : des résultats exacts',
    '(1/3)*3',
    '√2',
    'prix = 12,5',
    'prix * 3',
    '$2 + $5',
    '2 +',
  ];

  const linesElement = document.getElementById('lines');
  const modeSelect = document.getElementById('display-mode');
  const messageElement = document.getElementById('message');
  const undoButton = document.querySelector('[data-action="undo"]');
  const redoButton = document.querySelector('[data-action="redo"]');

  let current = null;
  // What the page says besides the refusals of the core: lasts until the next change, or for good when the storage fails.
  let notice = '';
  // The line the buttons act on: the last one that held the focus, since a tap on a button takes the focus away.
  let currentLine = 1;

  // ---- Drawing the lines ---------------------------------------------------------------------------------------------

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

  // A text typed in a field and not yet validated: it differs from the text the notebook holds for that line.
  function isPending(input) {
    return input.dataset.validated !== undefined && input.value !== input.dataset.validated;
  }

  // A row is kept from one display to the next, so that the field being typed in keeps its focus and its caret. The field
  // then shows the text the notebook holds, which is also the text Escape brings back: every command validates what was
  // typed before it runs. A change of mode is the exception (`keepTyped`): it validates nothing, so the focused field
  // keeps what is typed in it.
  function fill(row, line, keepTyped) {
    const number = row.firstElementChild;
    const input = number.nextElementSibling;
    row.setAttribute('data-line', String(line.number));
    row.setAttribute('data-status', line.status);
    number.textContent = String(line.number);
    input.setAttribute('aria-label', 'Ligne ' + line.number);
    if (input.value !== line.text && !(keepTyped && input === document.activeElement)) {
      input.value = line.text;
    }
    input.dataset.validated = line.text;
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

  function showMessage(text) {
    messageElement.textContent = text;
    messageElement.hidden = text === '';
  }

  function markCurrentLine() {
    Array.from(linesElement.children).forEach((row, index) => {
      row.classList.toggle('courante', index + 1 === currentLine);
    });
  }

  // True while the rows are being redrawn: a browser may report the loss of the focus of a row that is removed, and its
  // text is then the text of a line that is gone, not one to validate.
  let drawing = false;

  function render(keepTyped) {
    modeSelect.value = current.mode;
    const lines = current.lines.concat(draftLine(current.lines.length + 1));
    drawing = true;
    while (linesElement.children.length > lines.length) {
      linesElement.lastElementChild.remove();
    }
    lines.forEach((line, index) => {
      fill(linesElement.children[index] || linesElement.appendChild(newRow()), line, keepTyped);
    });
    drawing = false;
    inputOf(lines.length).placeholder = current.lines.length === 0 ? '(1/3)*3 puis Entrée' : '';
    undoButton.disabled = !current.canUndo;
    redoButton.disabled = !current.canRedo;
    currentLine = Math.min(currentLine, lines.length);
    markCurrentLine();
    showMessage(notice);
  }

  function inputOf(number) {
    return linesElement.children[number - 1].querySelector('input.expression');
  }

  function numberOf(input) {
    return Number(input.closest('[data-line]').getAttribute('data-line'));
  }

  function setCurrentLine(number) {
    currentLine = number;
    markCurrentLine();
  }

  function focusLine(number) {
    const input = inputOf(Math.max(1, Math.min(number, linesElement.children.length)));
    input.focus();
    return input;
  }

  // ---- Keeping the notebook in the browser ---------------------------------------------------------------------------

  // Every access to the storage is protected, the access to the property too: it can throw by itself.
  function save() {
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify({
        version: 1,
        mode: current.mode,
        lines: current.lines.map((line) => line.text),
      }));
    } catch (failure) {
      notice = NOT_KEPT_NOTICE;
    }
  }

  // The value read from the storage, or null when it is not the notebook this page writes.
  function readableNotebook(raw) {
    let value;
    try {
      value = JSON.parse(raw);
    } catch (failure) {
      return null;
    }
    if (value === null || typeof value !== 'object' || Array.isArray(value) || value.version !== 1
        || !MODES.includes(value.mode) || !Array.isArray(value.lines) || value.lines.length > MAX_LINES) {
      return null;
    }
    for (const line of value.lines) {
      if (typeof line !== 'string' || line.length > MAX_LINE_LENGTH) {
        return null;
      }
    }
    return value;
  }

  function loadExample(mode) {
    return JSON.parse(calc.load(EXAMPLE.join('\n'), mode));
  }

  function start() {
    let raw;
    try {
      raw = window.localStorage.getItem(STORAGE_KEY);
    } catch (failure) {
      notice = NOT_KEPT_NOTICE;
      current = loadExample('AUTO');
      return;
    }
    if (raw === null) {
      current = loadExample('AUTO');
      return;
    }
    const stored = readableNotebook(raw);
    if (stored !== null) {
      // A line read from the storage is one line: a line break in it would split it in two.
      const text = stored.lines.map((line) => line.replace(/[\r\n]/g, ' ')).join('\n');
      const snapshot = JSON.parse(calc.load(text, stored.mode));
      if (snapshot.message === null) {
        current = snapshot;
        return;
      }
    }
    notice = UNREADABLE_NOTICE;
    current = loadExample('AUTO');
  }

  // ---- Commands ------------------------------------------------------------------------------------------------------

  // False when the core refuses: the notebook did not change, and the field keeps what was typed so it can be corrected.
  function apply(answer, keepTyped) {
    const snapshot = JSON.parse(answer);
    if (snapshot.message !== null) {
      showMessage(snapshot.message);
      return false;
    }
    current = snapshot;
    if (notice === UNREADABLE_NOTICE) {
      notice = '';
    }
    save();
    render(keepTyped === true);
    return true;
  }

  function validate(number, text) {
    const line = current.lines[number - 1];
    if (line === undefined) {
      return text.trim() === '' || apply(calc.append(text));
    }
    return text === line.text || apply(calc.edit(number, text));
  }

  // A field left for the Annuler or Rétablir button with a text not yet validated: a tap moves the focus to the button
  // first, and leaving the field must not validate the text the button is about to take out or to keep.
  let heldField = null;

  function pendingField() {
    if (heldField !== null && heldField.isConnected && isPending(heldField)) {
      return heldField;
    }
    const active = document.activeElement;
    return active !== null && active.matches('input.expression') && isPending(active) ? active : null;
  }

  // A command acts on the notebook, so the text typed and not yet validated goes in first.
  function validatePending() {
    const field = pendingField();
    return field === null || validate(numberOf(field), field.value);
  }

  // Acts on the current line of the notebook; the answer of `act` is whether the notebook changed, and `next` the line
  // that follows the change. Only the keys take the focus back to the sheet: a tap would raise the keyboard of a phone.
  // The blank line that ends the sheet is not in the notebook yet. Like the desktop (NotebookPresenter), there is nothing
  // to delete or to move there (`draftMessage` says so, before anything typed is validated), and an insertion appends.
  function onCurrentLine(act, next, withFocus, draftMessage) {
    if (draftMessage !== null && currentLine > current.lines.length) {
      showMessage(draftMessage);
      return;
    }
    if (!validatePending()) {
      return;
    }
    const number = currentLine;
    if (number > current.lines.length) {
      if (apply(calc.append(''))) {
        setCurrentLine(current.lines.length);
        if (withFocus) {
          focusLine(currentLine);
        }
      }
      return;
    }
    if (!act(number)) {
      return;
    }
    setCurrentLine(next(number));
    if (withFocus) {
      focusLine(currentLine);
    }
  }

  function insertBelow(withFocus) {
    onCurrentLine((number) => apply(calc.insertAfter(number, '')), (number) => number + 1, withFocus, null);
  }

  function insertAbove(withFocus) {
    onCurrentLine((number) => apply(calc.insertBefore(number, '')), (number) => number, withFocus, null);
  }

  function deleteLine(withFocus) {
    onCurrentLine((number) => apply(calc.deleteLine(number)), (number) => number, withFocus,
      'Cette ligne n\'est pas encore validée : il n\'y a rien à supprimer (Échap abandonne la saisie).');
  }

  function moveLine(delta, withFocus) {
    const move = (number) => {
      if (number + delta < 1 || number + delta > current.lines.length) {
        showMessage(delta < 0 ? 'La ligne est déjà la première.' : 'La ligne est déjà la dernière.');
        return false;
      }
      return apply(calc.move(number, number + delta));
    };
    onCurrentLine(move, (number) => number + delta, withFocus,
      'Cette ligne n\'est pas encore validée : elle ne peut pas être déplacée.');
  }

  // Like the desktop: undo takes the text not yet validated out first, and only then a command of the notebook.
  function undo(withFocus) {
    const field = pendingField();
    heldField = null;
    if (field !== null) {
      field.value = field.dataset.validated;
      return;
    }
    if (apply(calc.undo()) && withFocus) {
      focusLine(currentLine);
    }
  }

  // Refused while a text is not validated, so that typing is never written over.
  function redo(withFocus) {
    const field = pendingField();
    heldField = null;
    if (field !== null) {
      showMessage('Validez la saisie en cours (Entrée) ou annulez-la (Échap) avant de rétablir.');
      // The text stays to be validated or abandoned, in its field.
      if (field !== document.activeElement) {
        field.focus();
      }
      return;
    }
    if (apply(calc.redo()) && withFocus) {
      focusLine(currentLine);
    }
  }

  function nextMode() {
    apply(calc.setMode(MODES[(MODES.indexOf(current.mode) + 1) % MODES.length]), true);
  }

  function clearNotebook() {
    if (validatePending() && apply(calc.clear())) {
      setCurrentLine(1);
    }
  }

  function loadExampleNotebook() {
    if (current.lines.length > 0 && !window.confirm('Remplacer le carnet par le carnet d\'exemple ?')) {
      return;
    }
    if (apply(calc.load(EXAMPLE.join('\n'), current.mode))) {
      setCurrentLine(1);
    }
  }

  const ACTIONS = {
    undo: () => undo(false),
    redo: () => redo(false),
    insert: () => insertBelow(false),
    up: () => moveLine(-1, false),
    down: () => moveLine(1, false),
    delete: () => deleteLine(false),
    clear: clearNotebook,
    example: loadExampleNotebook,
  };

  document.querySelectorAll('[data-action]').forEach((button) => {
    button.addEventListener('click', ACTIONS[button.getAttribute('data-action')]);
  });

  // ---- The keyboard --------------------------------------------------------------------------------------------------

  // The shortcuts of the desktop (NotebookShortcuts). Cmd does what Ctrl does, Cmd+M included: the desktop keeps Ctrl+M
  // only because Cmd+M minimises a window on macOS, where a browser may take it before the page. A key is taken from the
  // browser only when the page does something with it.
  function onMenuKey(event) {
    const key = event.key.length === 1 ? event.key.toLowerCase() : event.key;
    if (event.key === 'Enter') {
      (event.shiftKey ? insertAbove : insertBelow)(true);
    } else if (event.shiftKey && key === 'k') {
      deleteLine(true);
    } else if (event.shiftKey && (event.key === 'ArrowUp' || event.key === 'ArrowDown')) {
      moveLine(event.key === 'ArrowUp' ? -1 : 1, true);
    } else if (key === 'z' && !event.shiftKey) {
      undo(true);
    } else if (key === 'y' || (key === 'z' && event.shiftKey)) {
      redo(true);
    } else if (key === 'm' && !event.shiftKey) {
      nextMode();
    } else {
      return false;
    }
    return true;
  }

  function onLineKey(event, input) {
    const number = numberOf(input);
    if (event.key === 'Enter') {
      if (validate(number, input.value)) {
        focusLine(Math.min(number + 1, current.lines.length + 1));
      }
    } else if (event.key === 'Escape') {
      input.value = input.dataset.validated;
    } else if (event.key === 'ArrowUp' && number > 1) {
      focusLine(number - 1);
    } else if (event.key === 'ArrowDown' && number < linesElement.children.length) {
      focusLine(number + 1);
    } else {
      return false;
    }
    return true;
  }

  document.addEventListener('keydown', (event) => {
    if (event.isComposing || event.altKey) {
      return;
    }
    const menu = event.ctrlKey || event.metaKey;
    const input = event.target.matches('input.expression') ? event.target : null;
    let handled = false;
    if (menu) {
      handled = onMenuKey(event);
    } else if (input !== null) {
      handled = onLineKey(event, input);
    }
    if (handled) {
      event.preventDefault();
    }
  });

  // A field held for Annuler or Rétablir is validated after all when the focus goes anywhere else.
  function releaseHeldField() {
    const field = heldField;
    heldField = null;
    if (field !== null && field.isConnected && isPending(field)) {
      validate(numberOf(field), field.value);
    }
  }

  document.addEventListener('focusin', (event) => {
    if (heldField !== null && event.target !== undoButton && event.target !== redoButton) {
      releaseHeldField();
    }
    if (event.target.matches('input.expression')) {
      setCurrentLine(numberOf(event.target));
    }
  });

  // Leaving a line validates it too. Nothing happens when Enter has just done so: the text is already the one of the line.
  linesElement.addEventListener('focusout', (event) => {
    if (drawing || !event.target.matches('input.expression')) {
      return;
    }
    if ((event.relatedTarget === undoButton || event.relatedTarget === redoButton) && isPending(event.target)) {
      heldField = event.target;
      return;
    }
    validate(numberOf(event.target), event.target.value);
  });

  modeSelect.addEventListener('change', () => {
    apply(calc.setMode(modeSelect.value), true);
  });

  start();
  render();
  focusLine(current.lines.length + 1);
}());
