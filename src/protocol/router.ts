/**
 * 把一則請求送給對應的 handler。
 *
 * 不解碼、不做 IO、不自己拼結果——只決定誰來回答，以及所有請求都要先過的版本關卡。
 */
import { ErrorCode } from './errors.js';
import { failure, type JsonRpcNotification, type JsonRpcRequest, type JsonRpcResponse } from './messages.js';
import { readMeta } from './meta.js';
import { discover } from './methods/discover.js';
import { listTools } from './methods/toolsList.js';
import { callTool } from './methods/toolsCall.js';

export async function route(request: JsonRpcRequest): Promise<JsonRpcResponse> {
  // server/discover 不檢查版本：它正是「你會哪幾版」的問法，要求先講對版本才能問，
  // 等於要求 Client 先知道答案。規格也把它當成 stdio 上的新舊版探測手段。
  if (request.method === 'server/discover') {
    return discover(request.id);
  }

  const meta = readMeta(request.params, request.id);
  if (!meta.ok) {
    return meta.response;
  }

  switch (request.method) {
    case 'tools/list':
      return listTools(request.id);
    case 'tools/call':
      return await callTool(request);
    default:
      return failure(request.id, ErrorCode.MethodNotFound, `Unknown method: ${request.method}`);
  }
}

/**
 * 通知沒有回應。
 *
 * `notifications/cancelled` 在 stdio 上是 Client 取消請求的唯一途徑。本 Server 逐則
 * 序列處理，一則做完才讀下一則，取消通知抵達時該則早已回覆完畢，因此收下不做事；
 * 真正要處理的時機是工具開始做長時間的下游呼叫之後。未知的通知規格要求一律忽略。
 */
export function routeNotification(_notification: JsonRpcNotification): void {}
