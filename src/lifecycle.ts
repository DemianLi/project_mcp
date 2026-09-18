/**
 * 還在飛的請求。
 *
 * stdin 關閉是關機訊號，但關機不能比回應早。Transport 一旦關掉，SDK 要送回應時會拿到
 * 「已關閉」而把那一則丟掉；連線池一旦收掉，還在跑的查詢也會斷。所以關機的第一步是
 * 等這裡歸零。
 *
 * 只數 Tool 的呼叫：其他方法（`server/discover`、`tools/list`）都是記憶體裡的資料，
 * 同一個 tick 就回完了，等不等沒有差別。
 */
let inFlight = 0;
let notifyIdle: (() => void) | null = null;

export async function trackInFlight<T>(work: () => Promise<T>): Promise<T> {
  inFlight += 1;
  try {
    return await work();
  } finally {
    inFlight -= 1;
    if (inFlight === 0 && notifyIdle !== null) {
      const done = notifyIdle;
      notifyIdle = null;
      done();
    }
  }
}

/** 等到沒有請求在飛。只有關機流程會等。 */
export function whenIdle(): Promise<void> {
  if (inFlight === 0) {
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    notifyIdle = resolve;
  });
}

/** 測試用。 */
export function inFlightCount(): number {
  return inFlight;
}
