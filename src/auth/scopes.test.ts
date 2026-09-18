/**
 * 授權規則。純函式，不需要 Server。
 *
 * 兩條容易被誤讀的規則在這裡釘住：沒有驗證層時一律放行（因為沒有身分就沒有授權可言，
 * 而那種部署已被啟動守門限制在 loopback），以及「有身分但沒有任何 scope」跟前者完全不同。
 */
import { strictEqual } from 'node:assert/strict';
import { describe, it } from 'node:test';
import { Scope, allows } from './scopes.js';

describe('allows', () => {
  it('lets a Tool with no declared scope through for anyone authenticated', () => {
    strictEqual(allows([], undefined), true);
    strictEqual(allows(['notes:read'], undefined), true);
  });

  it('lets a holder of the scope through', () => {
    strictEqual(allows([Scope.NotesRead], Scope.NotesRead), true);
  });

  it('refuses a holder of a different scope', () => {
    strictEqual(allows([Scope.NotesRead], Scope.NotesDelete), false);
  });

  it('does not treat write as covering delete', () => {
    // 三級是並列的，不是包含的。要能刪就要明寫 notes:delete。
    strictEqual(allows([Scope.NotesRead, Scope.NotesWrite], Scope.NotesDelete), false);
  });

  it('refuses an authenticated caller holding nothing', () => {
    strictEqual(allows([], Scope.NotesRead), false);
  });

  it('allows everything when there is no authenticator at all', () => {
    // null 是「這台沒有驗證層」，跟上面那條的空陣列是兩回事。
    strictEqual(allows(null, Scope.NotesDelete), true);
  });
});
