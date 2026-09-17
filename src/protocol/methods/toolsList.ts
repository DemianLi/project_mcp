/**
 * `tools/list`：菜單。
 */
import { success, type JsonRpcId, type JsonRpcSuccess } from '../messages.js';
import { completeList } from '../results.js';
import { TOOLS, describe } from '../../tools/registry.js';

/** 菜單多久算新鮮。Tools 寫死在程式裡，一次啟動之內不會變，一分鐘是保守值。 */
const TTL_MS = 60_000;

export function listTools(id: JsonRpcId): JsonRpcSuccess {
  return success(id, completeList({ tools: TOOLS.map(describe) }, TTL_MS));
}
