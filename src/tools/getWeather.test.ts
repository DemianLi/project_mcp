import { deepStrictEqual, ok, strictEqual } from 'node:assert/strict';
import { describe, it } from 'node:test';
import { getWeather } from './getWeather.js';
import { Remedy } from './remedy.js';

function structured(body: { structuredContent: Readonly<Record<string, unknown>> }): Record<string, unknown> {
  return body.structuredContent as Record<string, unknown>;
}

describe('get_weather', () => {
  it('declares itself read-only', () => {
    strictEqual(getWeather.annotations?.['readOnlyHint'], true);
  });

  it('answers a known city in celsius by default', async () => {
    const body = await getWeather.call({ city: 'Taipei' });
    strictEqual(body.isError, false);
    deepStrictEqual(structured(body), {
      city: 'Taipei',
      units: 'celsius',
      temperature: 29,
      condition: 'thunderstorms',
    });
  });

  it('matches a city regardless of case and surrounding space', async () => {
    const body = await getWeather.call({ city: '  TOKYO ' });
    strictEqual(body.isError, false);
    strictEqual(structured(body)['condition'], 'cloudy');
  });

  it('converts to fahrenheit when asked', async () => {
    const body = await getWeather.call({ city: 'London', units: 'fahrenheit' });
    strictEqual(structured(body)['temperature'], 59);
  });

  it('says fix the request when the city is missing', async () => {
    const body = await getWeather.call({});
    strictEqual(body.isError, true);
    strictEqual(structured(body)['remedy'], Remedy.FixRequest);
  });

  it('says fix the request when the city is blank', async () => {
    const body = await getWeather.call({ city: '   ' });
    strictEqual(body.isError, true);
    strictEqual(structured(body)['remedy'], Remedy.FixRequest);
  });

  it('refuses units it does not know', async () => {
    const body = await getWeather.call({ city: 'Taipei', units: 'kelvin' });
    strictEqual(body.isError, true);
    strictEqual(structured(body)['remedy'], Remedy.FixRequest);
  });

  it('calls an unknown city a fixable request, and says which cities it knows', async () => {
    const body = await getWeather.call({ city: 'Atlantis' });
    strictEqual(body.isError, true);
    strictEqual(structured(body)['remedy'], Remedy.FixRequest);
    ok(Array.isArray(structured(body)['knownCities']));
  });

  it('says the same thing to the model and to the program', async () => {
    const body = await getWeather.call({ city: 'Taipei' });
    deepStrictEqual(JSON.parse(body.content[0]!.text), body.structuredContent);
  });
});
