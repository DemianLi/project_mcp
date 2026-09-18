/**
 * k8s 要問的兩個問題，答案不一樣。
 *
 * liveness：「這個行程還活著嗎？」答不出來就重啟。所以它不能去碰資料庫——資料庫掛掉時
 * 重啟 Server 沒有任何幫助，只會讓一場資料庫事故變成一場滾動重啟事故。
 *
 * readiness：「現在可以把流量送進來嗎？」資料庫連不上時答不，k8s 就把這個 Pod 從
 * Service 的後端名單拿掉，等它好了再放回去。
 */
import { readDatabaseConfig } from '../db/config.js';
import { query } from '../db/pool.js';

export interface Health {
  readonly ok: boolean;
  readonly detail: string;
}

/** 活著就是活著。能跑到這一行就是。 */
export function liveness(): Health {
  return { ok: true, detail: 'alive' };
}

export async function readiness(): Promise<Health> {
  const config = readDatabaseConfig();
  if (!config.ok) {
    // 沒設定資料庫是一種合法的部署：不碰資料庫的 Tool 照樣服務。
    return { ok: true, detail: 'ready, no database configured' };
  }
  try {
    await query('select 1');
    return { ok: true, detail: 'ready, database reachable' };
  } catch (cause) {
    return { ok: false, detail: cause instanceof Error ? cause.message : String(cause) };
  }
}
