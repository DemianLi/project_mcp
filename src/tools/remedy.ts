/**
 * 呼叫者下一步該做什麼。
 *
 * 依「可以採取的行動」分類，不依成因——兩個成因不同、但下一步相同的失敗，共用同一個
 * Remedy。工具失敗走 `isError: true` 的結果而不是 JSON-RPC error，模型才看得到它，
 * 也才有機會改參數再試。
 */
export const Remedy = {
  /** 原樣再呼叫一次。 */
  Retry: 'RETRY',
  /** 寫入的結果無法讀取：先確認是否已經寫入，再決定要不要重送。 */
  CheckBeforeRetry: 'CHECK_BEFORE_RETRY',
  /** 照原樣呼叫不可能成功，必須改參數。 */
  FixRequest: 'FIX_REQUEST',
  /** 呼叫者改不了任何事，要由人修正環境。 */
  AskOperator: 'ASK_OPERATOR',
  /** 無法辨識的失敗。 */
  Unknown: 'UNKNOWN',
} as const;

export type Remedy = (typeof Remedy)[keyof typeof Remedy];
