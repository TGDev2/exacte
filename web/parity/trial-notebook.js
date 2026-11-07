'use strict';
// Runs a notebook through WebExports.trialNotebook of the core compiled by TeaVM.
//   node web/parity/trial-notebook.js <calc.js> <notebook file> <mode> <moveNumber> <moveToNumber>
// The notebook goes through a UTF-8 file, never an argument: under Windows, ProcessBuilder encodes arguments in the code
// page of the system and would lose the square root sign.

const fs = require('fs');
const path = require('path');

function main([calcFile, notebookFile, mode, moveNumber, moveToNumber]) {
  const calc = require(path.resolve(calcFile));
  const lines = fs.readFileSync(notebookFile, 'utf8').split('\n').map((line) => line.replace(/\r+$/, '')).join('\n');
  process.stdout.write(calc.trialNotebook(lines, mode, Number(moveNumber), Number(moveToNumber)) + '\n');
}

try {
  main(process.argv.slice(2));
} catch (error) {
  console.error(String(error));
  process.exitCode = 1;
}
