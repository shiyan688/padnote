import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import test from 'node:test';

const require = createRequire(import.meta.url);
const {DOMParser, XMLSerializer} = require('@xmldom/xmldom') as {
  DOMParser: new () => {parseFromString(source: string, mime: string): any};
  XMLSerializer: new () => {serializeToString(node: unknown): string};
};
const sre = require('speech-rule-engine') as {
  engineReady(): Promise<unknown>;
  toSpeech(mathml: string): string;
};

const mathml = '<math xmlns="http://www.w3.org/1998/Math/MathML" xml:lang="zh-CN">' +
  '<mrow><mi>函数</mi><mo>=</mo><mfrac><mrow><mi>d</mi><mi>y</mi></mrow>' +
  '<mrow><mi>d</mi><mi>x</mi></mrow></mfrac><mo>⇒</mo>' +
  '<mrow><msubsup><mo>∫</mo><mn>0</mn><mi>π</mi></msubsup>' +
  '<mi>sin</mi><mo>(</mo><mi>x</mi><mo>)</mo><mi>d</mi><mi>x</mi></mrow></mrow></math>';

test('locked xmldom parses and serializes Chinese MathML consumed by speech-rule-engine', async () => {
  assert.equal(require('@xmldom/xmldom/package.json').version, '0.9.12');
  const parser = new DOMParser();
  const document = parser.parseFromString(mathml, 'application/xml');
  assert.equal(document.documentElement.localName, 'math');
  assert.equal(document.documentElement.namespaceURI, 'http://www.w3.org/1998/Math/MathML');
  assert.match(new XMLSerializer().serializeToString(document), /函数/);

  await sre.engineReady();
  const speech = sre.toSpeech(mathml);
  assert.equal(typeof speech, 'string');
  assert.ok(speech.trim().length > 0);
});
