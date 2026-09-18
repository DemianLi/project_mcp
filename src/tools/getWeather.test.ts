/**
 * 兩件事分開測：schema 擋什麼，本體回什麼。
 *
 * schema 現在是真的在做事的東西（SDK 靠它驗參數），所以直接對它下斷言，而不是繞過它
 * 呼叫本體再假裝參數沒問題。
 */
import { deepStrictEqual, ok, strictEqual } from 'node:assert/strict';
import { describe, it } from 'node:test';
import { getWeather, readWeather, weatherInput } from './getWeather.js';
import { Remedy } from './remedy.js';

function structured(body: { structuredContent: Readonly<Record<string, unknown>> }): Record<string, unknown> {
  return body.structuredContent as Record<string, unknown>;
}

/** 驗過之後的參數。schema 擋不下來才輪到本體。 */
function parse(args: Record<string, unknown>): { city: string; units: 'celsius' | 'fahrenheit' } {
  return weatherInput.parse(args);
}

describe('get_weather input schema', () => {
  it('fills in celsius when units are not given', () => {
    deepStrictEqual(parse({ city: 'Taipei' }), { city: 'Taipei', units: 'celsius' });
  });

  it('trims the city before anyone sees it', () => {
    strictEqual(parse({ city: '  TOKYO ' }).city, 'TOKYO');
  });

  it('rejects a missing city', () => {
    strictEqual(weatherInput.safeParse({}).success, false);
  });

  it('rejects a city that is nothing but space', () => {
    strictEqual(weatherInput.safeParse({ city: '   ' }).success, false);
  });

  it('rejects units it does not know', () => {
    strictEqual(weatherInput.safeParse({ city: 'Taipei', units: 'kelvin' }).success, false);
  });
});

describe('get_weather', () => {
  it('is registered under the name it declares', () => {
    strictEqual(getWeather.name, 'get_weather');
  });

  it('answers a known city in celsius by default', async () => {
    const body = await readWeather(parse({ city: 'Taipei' }));
    strictEqual(body.isError, false);
    deepStrictEqual(structured(body), {
      city: 'Taipei',
      units: 'celsius',
      temperature: 29,
      condition: 'thunderstorms',
    });
  });

  it('matches a city regardless of case', async () => {
    const body = await readWeather(parse({ city: '  TOKYO ' }));
    strictEqual(body.isError, false);
    strictEqual(structured(body)['condition'], 'cloudy');
  });

  it('converts to fahrenheit when asked', async () => {
    const body = await readWeather(parse({ city: 'London', units: 'fahrenheit' }));
    strictEqual(structured(body)['temperature'], 59);
  });

  it('calls an unknown city a fixable request, and says which cities it knows', async () => {
    const body = await readWeather(parse({ city: 'Atlantis' }));
    strictEqual(body.isError, true);
    strictEqual(structured(body)['remedy'], Remedy.FixRequest);
    ok(Array.isArray(structured(body)['knownCities']));
  });

  it('says the same thing to the model and to the program', async () => {
    const body = await readWeather(parse({ city: 'Taipei' }));
    deepStrictEqual(JSON.parse(body.content[0]!.text), body.structuredContent);
  });
});
