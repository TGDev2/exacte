'use strict';
// Replays the golden file against the core compiled by TeaVM and compares each case like GoldenOutputsTest.
//   node web/parity/replay-golden.js <calc.js> <golden.tsv>
// Succeeds with the last line "OK 70/70" and code 0; otherwise code 1 and "ligne <n> : ..." on stderr for each gap.

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const EXPECTED_CASES = 70;
const REPEAT = /<rep:(\d+):([^>]*)>/g;
const UNICODE_ESCAPE = /<U\+([0-9A-F]{4})>/g;
const HASHED = /^len=\d+ sha256=[0-9a-f]{64}$/;

/** The notations of the golden file, in the order of GoldenOutputsTest.expand. */
function expand(notation) {
  return notation
    .replace(REPEAT, (match, count, text) => text.repeat(Number(count)))
    .replace(UNICODE_ESCAPE, (match, code) => String.fromCharCode(parseInt(code, 16)));
}

/** GoldenOutputsTest.comparable: length in UTF-16 units, digest of the UTF-8 bytes. */
function comparable(expected, actual) {
  if (!HASHED.test(expected)) {
    return actual;
  }
  const digest = crypto.createHash('sha256').update(actual, 'utf8').digest('hex');
  return `len=${actual.length} sha256=${digest}`;
}

function gapOf(columns, actualLine) {
  const [outcome, display, reusable] = actualLine.split('\t');
  const expectedOutcome = columns[3];
  const expectedDisplay = columns[4];
  const expectedReusable = columns[5];
  if (outcome !== expectedOutcome) {
    return `issue ${outcome} au lieu de ${expectedOutcome}`;
  }
  if (comparable(expectedDisplay, display) !== expectedDisplay) {
    return `affichage « ${display} » au lieu de « ${expectedDisplay} »`;
  }
  if (comparable(expectedReusable, reusable) !== expectedReusable) {
    return `valeur réutilisable « ${reusable} » au lieu de « ${expectedReusable} »`;
  }
  return null;
}

function replay(calc, content) {
  // Split on line feeds and remove the end-of-line carriage returns only: empty entries count, so nothing is trimmed.
  const lines = content.split('\n').map((line) => line.replace(/\r+$/, ''));
  const gaps = [];
  let cases = 0;
  for (let index = 1; index < lines.length; index++) {
    if (lines[index] === '') {
      continue;
    }
    cases++;
    const number = index + 1;
    const columns = lines[index].split('\t');
    if (columns.length !== 6) {
      gaps.push(`ligne ${number} : ${columns.length} colonnes au lieu de 6`);
      continue;
    }
    try {
      const gap = gapOf(columns, calc.goldenCase(columns[0], expand(columns[1]), expand(columns[2])));
      if (gap !== null) {
        gaps.push(`ligne ${number} : ${gap}`);
      }
    } catch (error) {
      gaps.push(`ligne ${number} : exception ${error}`);
    }
  }
  if (cases !== EXPECTED_CASES) {
    gaps.push(`ligne ${lines.length} : ${cases} cas au lieu de ${EXPECTED_CASES}`);
  }
  return gaps;
}

function main([calcFile, goldenFile]) {
  const calc = require(path.resolve(calcFile));
  const gaps = replay(calc, fs.readFileSync(goldenFile, 'utf8'));
  if (gaps.length > 0) {
    gaps.forEach((gap) => console.error(gap));
    return 1;
  }
  console.log(`OK ${EXPECTED_CASES}/${EXPECTED_CASES}`);
  return 0;
}

try {
  process.exitCode = main(process.argv.slice(2));
} catch (error) {
  console.error(String(error));
  process.exitCode = 1;
}
