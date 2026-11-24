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
  const palette = document.getElementById('command-palette');
  const searchField = document.getElementById('command-search');
  const commandList = document.getElementById('command-list');
  const emptyElement = document.getElementById('command-empty');
  const paletteButton = document.getElementById('open-palette');
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

  // The margin of a line says whether its value is exact, like the desktop; the title spells it out for a pointer.
  const SIGNS = {EXACT: '=', APPROXIMATE: '≈', ERROR: '!'};
  const SIGN_TITLES = {EXACT: 'Valeur exacte', APPROXIMATE: 'Valeur approchée, avec son erreur maximale', ERROR: 'Erreur'};
  // The mark of a line the current line reads.
  const SOURCE_MARK = '◆';

  // A row: the margin (number, sign, source mark), the field, the name of an assignment and the result; under them the
  // error and the detail. The parts are kept on the row, which is reused from one display to the next.
  function newRow() {
    const row = document.createElement('li');
    row.className = 'line';
    const number = element('span', 'number', '');
    number.setAttribute('aria-hidden', 'true');
    const sign = element('span', 'sign', '');
    const sourceMark = element('span', 'source-mark', '');
    sourceMark.setAttribute('aria-hidden', 'true');
    const margin = element('span', 'marge', '');
    margin.append(number, sign, sourceMark);
    const input = document.createElement('input');
    input.className = 'expression';
    input.type = 'text';
    input.autocomplete = 'off';
    input.spellcheck = false;
    input.setAttribute('autocapitalize', 'off');
    input.setAttribute('enterkeyhint', 'next');
    const name = element('span', 'name', '');
    const result = element('span', 'result', '');
    row.append(margin, input, name, result);
    row.parts = {number: number, sign: sign, sourceMark: sourceMark, input: input, name: name, result: result, detail: null};
    return row;
  }

  // A line whose text starts with # is a title: the field keeps the raw text, only the typography changes.
  function isTitle(line) {
    return /^\s*#/.test(line.text);
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

  function errorParts(line) {
    return line.error === null ? [] : [element('div', 'error', 'Erreur : ' + line.error), faultyPart(line)];
  }

  // Under the current line, the sentences of the detail the core wrote (LineDetailText), one paragraph each: the formulas
  // in the monospace face, the sentences in the face of the page. Under any other line, only what the result cannot say
  // by itself: the maximum error of an approximate value, or the exact value behind a cut one.
  function detailOf(line, isCurrent) {
    if (isCurrent && line.detail.length > 0) {
      const detail = element('div', 'detail detail-courant', '');
      detail.append(...line.detail.map((sentence) => element('p', /^\p{L}/u.test(sentence) ? 'phrase' : 'formule', sentence)));
      return detail;
    }
    if (line.errorBound !== null) {
      return element('div', 'detail', 'Erreur maximale : ' + line.errorBound);
    }
    if (line.exactFraction !== null && line.result.includes(CUT_MARK)) {
      return element('div', 'detail', 'Valeur exacte : ' + line.exactFraction);
    }
    return null;
  }

  function placeDetail(row, isCurrent) {
    if (row.parts.detail !== null) {
      row.parts.detail.remove();
    }
    row.parts.detail = detailOf(row.line, isCurrent);
    if (row.parts.detail !== null) {
      row.append(row.parts.detail);
    }
  }

  // A text typed in a field and not yet validated: it differs from the text the notebook holds for that line.
  function isPending(input) {
    return input.dataset.validated !== undefined && input.value !== input.dataset.validated;
  }

  // A row is kept from one display to the next, so that the field being typed in keeps its focus and its caret. The field
  // then shows the text the notebook holds, which is also the text Escape brings back: every command validates what was
  // typed before it runs. A change of mode is the exception (`keepTyped`): it validates nothing, so the focused field
  // keeps what is typed in it.
  // The row is drawn as no line were current: markCurrentLine adds the detail and the source marks afterwards.
  function fill(row, line, keepTyped) {
    const parts = row.parts;
    const input = parts.input;
    row.line = line;
    row.setAttribute('data-line', String(line.number));
    row.setAttribute('data-status', line.status);
    row.classList.toggle('titre', isTitle(line));
    row.classList.remove('courante', 'source');
    parts.number.textContent = String(line.number);
    parts.sign.textContent = SIGNS[line.status] || '';
    parts.sign.title = SIGN_TITLES[line.status] || '';
    parts.sourceMark.textContent = '';
    input.setAttribute('aria-label', 'Ligne ' + line.number);
    if (input.value !== line.text && !(keepTyped && input === document.activeElement)) {
      input.value = line.text;
    }
    input.dataset.validated = line.text;
    parts.name.textContent = line.name === null ? '' : line.name;
    parts.result.textContent = line.result === null ? '' : line.result;
    while (parts.result.nextSibling !== null) {
      parts.result.nextSibling.remove();
    }
    parts.detail = null;
    row.append(...errorParts(line));
    placeDetail(row, false);
  }

  // Like the desktop, the sheet always ends with a line not yet in the notebook: typing there appends it.
  function draftLine(number) {
    return {
      number: number, text: '', status: 'EMPTY', result: null, exactFraction: null, errorBound: null, error: null,
      errorStart: -1, errorEnd: -1, name: null, sources: [], usedBy: [], detail: [],
    };
  }

  function showMessage(text) {
    messageElement.textContent = text;
    messageElement.hidden = text === '';
  }

  // What the current line marks: itself, with its detail, and its sources, with « ◆ » in their margin. Only the rows
  // whose marks change are touched, so that moving in a long notebook stays quick.
  let marked = {line: 0, sources: []};

  function decorate(number) {
    const row = linesElement.children[number - 1];
    if (row === undefined) {
      return;
    }
    const isCurrent = number === marked.line;
    const isSource = marked.sources.includes(number);
    row.classList.toggle('courante', isCurrent);
    row.classList.toggle('source', isSource);
    row.parts.sourceMark.textContent = isSource ? SOURCE_MARK : '';
    placeDetail(row, isCurrent);
  }

  function markCurrentLine() {
    const before = [marked.line].concat(marked.sources);
    const line = current.lines[currentLine - 1];
    marked = {line: currentLine, sources: line === undefined ? [] : line.sources};
    new Set(before.concat(currentLine, marked.sources)).forEach(decorate);
  }

  // True while the rows are being redrawn: a browser may report the loss of the focus of a row that is removed, and its
  // text is then the text of a line that is gone, not one to validate.
  let drawing = false;

  function render(keepTyped) {
    modeSelect.value = current.mode;
    const lines = current.lines.concat(draftLine(current.lines.length + 1));
    drawing = true;
    marked = {line: 0, sources: []};
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

  // ---- The theme -----------------------------------------------------------------------------------------------------

  // The theme chosen in the palette, kept apart from the notebook: « clair » or « sombre ». Without a choice (the
  // « Système » entry removes it) the root has no data-theme and tokens.css follows prefers-color-scheme.
  const THEME_KEY = 'calc.web.theme.v1';
  const THEMES = [{name: 'Clair', value: 'clair'}, {name: 'Sombre', value: 'sombre'}, {name: 'Système', value: null}];

  function applyTheme(value) {
    if (value === null) {
      document.documentElement.removeAttribute('data-theme');
    } else {
      document.documentElement.setAttribute('data-theme', value);
    }
  }

  // Every access to the storage is protected, the access to the property too: a refused storage only loses the memory of
  // the choice, never the choice itself.
  function storedTheme() {
    try {
      const value = window.localStorage.getItem(THEME_KEY);
      return value === 'clair' || value === 'sombre' ? value : null;
    } catch (failure) {
      return null;
    }
  }

  function chooseTheme(value) {
    applyTheme(value);
    try {
      if (value === null) {
        window.localStorage.removeItem(THEME_KEY);
      } else {
        window.localStorage.setItem(THEME_KEY, value);
      }
    } catch (failure) {
      // The theme lasts until the page is closed.
    }
  }

  // ---- The command palette -------------------------------------------------------------------------------------------

  const NO_COMMAND_TEXT = 'Aucune commande';
  const NEXT_MODE_LABEL = 'Mode suivant';
  const NEXT_MODE_SHORTCUT = 'Ctrl+M';

  // The commands, drawn from the page each time the palette opens: the buttons of the toolbar (their visible text is the
  // label), the options of #display-mode (« Mode » and the text of the option), the next mode and the themes. Like the
  // desktop: a command that cannot run now (Annuler with nothing to undo) is listed, greyed, and does nothing.
  function paletteCommands() {
    const commands = [];
    document.querySelectorAll('[data-action]').forEach((button) => {
      commands.push({
        label: button.textContent.trim(),
        shortcut: button.dataset.shortcut || '',
        enabled: !button.disabled,
        run: () => button.click(),
      });
    });
    Array.from(modeSelect.options).forEach((option) => {
      commands.push({
        label: 'Mode ' + option.textContent.trim(),
        shortcut: '',
        enabled: true,
        run: () => apply(calc.setMode(option.value), true),
      });
    });
    commands.push({label: NEXT_MODE_LABEL, shortcut: NEXT_MODE_SHORTCUT, enabled: true, run: nextMode});
    THEMES.forEach((theme) => {
      commands.push({label: 'Thème : ' + theme.name, shortcut: '', enabled: true, run: () => chooseTheme(theme.value)});
    });
    return commands;
  }

  // Case and accents ignored: « decimal » finds « Mode Décimal ».
  function searchable(text) {
    return text.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
  }

  let allCommands = [];
  let shownCommands = [];
  let selected = 0;

  function select(index) {
    selected = index;
    const options = commandList.children;
    Array.from(options).forEach((option, position) => {
      option.setAttribute('aria-selected', position === index ? 'true' : 'false');
    });
    if (options[index] === undefined) {
      searchField.removeAttribute('aria-activedescendant');
      return;
    }
    searchField.setAttribute('aria-activedescendant', options[index].id);
    options[index].scrollIntoView({block: 'nearest'});
  }

  function drawCommands() {
    commandList.replaceChildren(...shownCommands.map((command, index) => {
      const option = element('li', 'command', '');
      option.id = 'command-option-' + index;
      option.setAttribute('role', 'option');
      option.setAttribute('aria-selected', 'false');
      if (!command.enabled) {
        option.setAttribute('aria-disabled', 'true');
      }
      option.append(element('span', 'command-label', command.label));
      if (command.shortcut !== '') {
        option.append(element('span', 'command-shortcut', command.shortcut));
      }
      return option;
    }));
    emptyElement.textContent = shownCommands.length === 0 ? NO_COMMAND_TEXT : '';
    emptyElement.hidden = shownCommands.length > 0;
    select(0);
  }

  function filterCommands() {
    const wanted = searchable(searchField.value.trim());
    shownCommands = allCommands.filter((command) => searchable(command.label).includes(wanted));
    drawCommands();
  }

  function openPalette() {
    if (palette.hidden) {
      allCommands = paletteCommands();
      searchField.value = '';
      palette.hidden = false;
      filterCommands();
    }
    searchField.focus();
    searchField.select();
  }

  function hidePalette() {
    palette.hidden = true;
  }

  // The palette goes first, then the command runs, then the focus goes back to the current line: a command never runs
  // while the palette still owns the focus, and a late focus event cannot make another line the current one.
  function closePalette(command) {
    hidePalette();
    if (command !== undefined) {
      command.run();
    }
    focusLine(currentLine);
  }

  function runSelected() {
    const command = shownCommands[selected];
    if (command !== undefined && command.enabled) {
      closePalette(command);
    }
  }

  function onPaletteKey(event) {
    const key = event.key.length === 1 ? event.key.toLowerCase() : event.key;
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      select(Math.max(0, Math.min(selected + (event.key === 'ArrowDown' ? 1 : -1), shownCommands.length - 1)));
    } else if (event.key === 'Enter') {
      runSelected();
    } else if (event.key === 'Escape') {
      closePalette();
    } else if (!((event.ctrlKey || event.metaKey) && !event.shiftKey && key === 'k')) {
      return;
    }
    event.preventDefault();
  }

  paletteButton.addEventListener('click', openPalette);
  searchField.addEventListener('input', filterCommands);
  // A tap or a click on an entry runs it; the field keeps the focus until then, or the palette would close first.
  commandList.addEventListener('mousedown', (event) => event.preventDefault());
  commandList.addEventListener('click', (event) => {
    const option = event.target.closest('[role="option"]');
    if (option !== null) {
      select(Array.from(commandList.children).indexOf(option));
      runSelected();
    }
  });
  // The palette is not modal: Tab or a click elsewhere leaves it, without running anything.
  palette.addEventListener('focusout', (event) => {
    if (event.relatedTarget !== null && !palette.contains(event.relatedTarget)) {
      hidePalette();
    }
  });
  document.addEventListener('click', (event) => {
    if (!palette.hidden && !palette.contains(event.target) && !paletteButton.contains(event.target)) {
      hidePalette();
    }
  });

  // ---- The keyboard --------------------------------------------------------------------------------------------------

  // The shortcuts of the desktop (NotebookShortcuts). Cmd does what Ctrl does, Cmd+M included: the desktop keeps Ctrl+M
  // only because Cmd+M minimises a window on macOS, where a browser may take it before the page. A key is taken from the
  // browser only when the page does something with it: Ctrl+K and Cmd+K now open the palette, the others stay its own.
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
    } else if (key === 'k' && !event.shiftKey) {
      openPalette();
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
    // Inside the palette the keys are its own, and the text field keeps Ctrl+Z, Ctrl+A and the others for itself.
    if (palette.contains(event.target)) {
      onPaletteKey(event);
      return;
    }
    const menu =event.ctrlKey || event.metaKey;
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

  applyTheme(storedTheme());
  start();
  render();
  focusLine(current.lines.length + 1);
}());
