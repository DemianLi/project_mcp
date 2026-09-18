/**
 * `get_weather`：一個 Tool 的最小範本。
 *
 * 它不連網、不碰資料庫，回的是寫死的資料——存在的目的是示範一個 Tool 的四個部位：
 * 怎麼宣告自己、怎麼驗參數、怎麼回成功、怎麼回失敗。照著換掉 `call` 裡面就是一個
 * 真的 Tool。
 */
import { Remedy } from './remedy.js';
import { ok, failed, type CallToolBody } from './result.js';
import type { ToolDefinition } from './definition.js';

const UNITS = ['celsius', 'fahrenheit'] as const;
type Unit = (typeof UNITS)[number];

/** 寫死的觀測值。真的 Tool 在這裡呼叫下游。 */
const READINGS: ReadonlyMap<string, { celsius: number; condition: string }> = new Map([
  ['taipei', { celsius: 29, condition: 'thunderstorms' }],
  ['tokyo', { celsius: 24, condition: 'cloudy' }],
  ['london', { celsius: 15, condition: 'rain' }],
  ['san francisco', { celsius: 18, condition: 'fog' }],
]);

export const getWeather: ToolDefinition = {
  name: 'get_weather',
  title: 'Get weather',
  description:
    "A city's current weather. Cities are limited to a fixed sample set; this Tool is a template, not a weather service.",
  inputSchema: {
    type: 'object',
    properties: {
      city: { type: 'string', description: 'City name, e.g. "Taipei".' },
      units: { type: 'string', enum: [...UNITS], description: 'Defaults to celsius.' },
    },
    required: ['city'],
    additionalProperties: false,
  },
  // Client MUST 把這些當成不可信的自述。Server 不因為它們改變行為。
  annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: false },
  call: async (args): Promise<CallToolBody> => {
    const city = args['city'];
    if (typeof city !== 'string' || city.trim() === '') {
      return failed(Remedy.FixRequest, 'city is required and must be a non-empty string');
    }

    const units = args['units'] ?? 'celsius';
    if (!isUnit(units)) {
      return failed(Remedy.FixRequest, `units must be one of: ${UNITS.join(', ')}`);
    }

    const reading = READINGS.get(city.trim().toLowerCase());
    if (reading === undefined) {
      // 換一個城市就會成功，所以是 FIX_REQUEST，不是 RETRY。
      return failed(Remedy.FixRequest, `No reading for city: ${city}`, {
        knownCities: [...READINGS.keys()],
      });
    }

    return ok({
      city: city.trim(),
      units,
      temperature: units === 'celsius' ? reading.celsius : toFahrenheit(reading.celsius),
      condition: reading.condition,
    });
  },
};

function isUnit(value: unknown): value is Unit {
  return typeof value === 'string' && (UNITS as readonly string[]).includes(value);
}

function toFahrenheit(celsius: number): number {
  return Math.round(celsius * 1.8 + 32);
}
