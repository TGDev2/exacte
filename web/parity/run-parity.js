'use strict';
// Replays the corpus of the differential test against the core compiled by TeaVM.
//   node web/parity/run-parity.js <calc.js> <corpus> <output>
// Each line "<id>\t<base64 UTF-8 of the lines>" gives "<id>\t<base64 UTF-8 of parityNotebook>", in the order of the corpus.
// The comparison with the JVM is done by ParityAcceptanceTest. An exception ends the script with code 1.

const fs = require('fs');
const path = require('path');

function replay(calc, corpus) {
  const output = [];
  for (const raw of corpus.split('\n')) {
    // Only the end-of-line carriage return goes: an entry may hold an empty notebook, and nothing is trimmed.
    const line = raw.replace(/\r+$/, '');
    if (line === '') {
      continue;
    }
    const tab = line.indexOf('\t');
    if (tab < 0) {
      throw new Error(`not an entry of the corpus: ${line}`);
    }
    const lines = Buffer.from(line.slice(tab + 1), 'base64').toString('utf8');
    const snapshots = calc.parityNotebook(lines);
    output.push(`${line.slice(0, tab)}\t${Buffer.from(snapshots, 'utf8').toString('base64')}`);
  }
  return output;
}

function main([calcFile, corpusFile, outputFile]) {
  const calc = require(path.resolve(calcFile));
  const output = replay(calc, fs.readFileSync(corpusFile, 'utf8'));
  fs.mkdirSync(path.dirname(path.resolve(outputFile)), { recursive: true });
  fs.writeFileSync(outputFile, `${output.join('\n')}\n`, 'utf8');
}

try {
  main(process.argv.slice(2));
} catch (error) {
  console.error(String(error));
  process.exitCode = 1;
}
