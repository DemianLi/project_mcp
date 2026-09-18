/**
 * `get_weather`：一個 Tool 的最小範本。
 *
 * 它不連網、不碰資料庫，回的是寫死的資料——存在的目的是示範一個 Tool 的四個部位：
 * 怎麼宣告自己、怎麼驗參數、怎麼回成功、怎麼回失敗。照著換掉 `readWeather` 裡面就是一個
 * 真的 Tool。
 *
 * 驗參數分兩層，界線是「誰的錯」：形狀不對（少了 city、units 不在列表裡）由 schema 擋下，
 * SDK 直接回 JSON-RPC 錯誤；形狀對但問不到答案（沒有這個城市的觀測值）是 Tool 的失敗，
 * 走 `isError: true` 的結果帶一個 Remedy，模型才看得到、才有機會改參數再試。
 */
import { z } from 'zod';
import { defineTool } from './definition.js';
import { Remedy } from './remedy.js';
import { ok, failed, type CallToolBody } from './result.js';

/** 寫死的觀測值。真的 Tool 在這裡呼叫下游。 */
const READINGS: ReadonlyMap<string, { celsius: number; condition: string }> = new Map([
  ['taipei', { celsius: 29, condition: 'thunderstorms' }],
  ['tokyo', { celsius: 24, condition: 'cloudy' }],
  ['london', { celsius: 15, condition: 'rain' }],
  ['san francisco', { celsius: 18, condition: 'fog' }],
]);

/** 參數的形狀。SDK 拿它驗、也拿它轉成 JSON Schema 給模型看，所以 `describe` 不是註解。 */
export const weatherInput = z.object({
  city: z.string().trim().min(1).describe('City name, e.g. "Taipei".'),
  units: z.enum(['celsius', 'fahrenheit']).default('celsius').describe('Defaults to celsius.'),
});

/** Tool 的本體。參數已經驗過，所以這裡只處理「查不到」這一種失敗。 */
export async function readWeather({ city, units }: z.output<typeof weatherInput>): Promise<CallToolBody> {
  const reading = READINGS.get(city.toLowerCase());
  if (reading === undefined) {
    // 換一個城市就會成功，所以是 FIX_REQUEST，不是 RETRY。
    return failed(Remedy.FixRequest, `No reading for city: ${city}`, {
      knownCities: [...READINGS.keys()],
    });
  }

  return ok({
    city,
    units,
    temperature: units === 'celsius' ? reading.celsius : toFahrenheit(reading.celsius),
    condition: reading.condition,
  });
}

export const getWeather = defineTool({
  name: 'get_weather',
  title: 'Get weather',
  description:
    "A city's current weather. Cities are limited to a fixed sample set; this Tool is a template, not a weather service.",
  inputSchema: weatherInput,
  // Client MUST 把這些當成不可信的自述。Server 不因為它們改變行為。
  annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: false },
  call: readWeather,
});

function toFahrenheit(celsius: number): number {
  return Math.round(celsius * 1.8 + 32);
}
