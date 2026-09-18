/**
 * 「這個請求是誰送來的」。
 *
 * 這是領域概念，不是傳輸概念：它回答的是哪一個機關，而不是用了什麼憑證形式。所以它不是
 * SDK 的 `AuthInfo`——日後如果改用機關憑證（mTLS）認身分，換掉的是產生它的那一段，
 * Tool、稽核、`requestState` 都不動。
 */

export interface Principal {
  /** 機關代碼。稽核紀錄與多回合狀態綁的都是它。 */
  readonly agency: string;
  /** 這個身分被允許做什麼。第三段的每個 Tool 授權會用到。 */
  readonly scopes: readonly string[];
  /** 憑證到期時間（epoch 秒）。 */
  readonly expiresAt: number;
}

/**
 * 認不出身分。
 *
 * `status` 分兩種是因為 HTTP 要回不同的碼，而且意思不同：401 是「你沒表明身分，或表明的
 * 不算數」，403 是「認得你，但你不能做這件事」。
 */
export class AuthError extends Error {
  readonly status: 401 | 403;
  /** 給 `WWW-Authenticate` 用的簡短代碼。不要把細節寫進去——那是給攻擊者的提示。 */
  readonly code: string;

  constructor(status: 401 | 403, code: string, message: string) {
    super(message);
    this.name = 'AuthError';
    this.status = status;
    this.code = code;
  }
}

/**
 * 沒有驗證層時用的身分。
 *
 * 開發時走 stdio，能啟動子行程的人就是擁有者，問「是誰」沒有意義。但稽核紀錄與
 * `requestState` 還是需要一個值，所以給一個明確寫著「沒認過」的，而不是空字串——
 * log 裡看到它就知道那台 Server 沒有驗證層。
 */
export const ANONYMOUS: Principal = {
  agency: 'anonymous',
  scopes: [],
  expiresAt: 0,
};
