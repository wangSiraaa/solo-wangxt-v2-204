// 极简 API 客户端：令牌存 localStorage；POST 自动携带/复用 requestId 保证重试幂等。
const TOKEN_KEY = 'craft_token'
const SESSION_KEY = 'craft_session'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function getSession() {
  try {
    return JSON.parse(localStorage.getItem(SESSION_KEY) || 'null')
  } catch {
    return null
  }
}

export function setAuth(token, session) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(SESSION_KEY, JSON.stringify(session))
}

export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(SESSION_KEY)
}

function newRequestId() {
  return 'web-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10)
}

/** 502/503/504/网络错误时用同一 requestId 重试：服务端幂等键保证不重复下单。 */
async function request(method, path, body, { retry = 2, requestId = newRequestId() } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  const token = getToken()
  if (token) headers.Authorization = 'Bearer ' + token
  if (body && method !== 'GET') body.requestId = body.requestId || requestId

  let lastErr
  for (let attempt = 0; attempt <= retry; attempt++) {
    let resp
    try {
      resp = await fetch(path, {
        method,
        headers,
        body: method === 'GET' ? undefined : JSON.stringify(body || {})
      })
    } catch (networkErr) {
      lastErr = new Error('网络异常：' + networkErr.message + '（将以相同 requestId 重试）')
      await new Promise((r) => setTimeout(r, 200 * (attempt + 1)))
      continue
    }
    const text = await resp.text()
    const data = text ? JSON.parse(text) : {}
    if (!resp.ok) {
      const err = new Error(data.message || ('HTTP ' + resp.status))
      err.status = resp.status
      err.data = data
      // 409 等业务冲突不重试；只有网关类错误才幂等重试
      if ([502, 503, 504].includes(resp.status) && attempt < retry) {
        await new Promise((r) => setTimeout(r, 200 * (attempt + 1)))
        lastErr = err
        continue
      }
      throw err
    }
    return { data, requestId: body?.requestId || requestId }
  }
  throw lastErr
}

export const api = {
  login: (username, password) => request('POST', '/api/auth/login', { username, password }, { retry: 0 }),
  logout: () => request('POST', '/api/auth/logout', {}, { retry: 0 }),

  playerRecipes: () => request('GET', '/api/player/recipes'),
  preview: (versionId) => request('GET', `/api/player/recipes/${versionId}/preview`),
  craft: (versionId, qty, requestId) => request('POST', '/api/player/craft', { versionId, qty }, { requestId }),
  complete: (orderNo) => request('POST', `/api/player/orders/${orderNo}/complete`, {}),
  cancel: (orderNo) => request('POST', `/api/player/orders/${orderNo}/cancel`, {}),
  orders: () => request('GET', '/api/player/orders'),
  order: (orderNo) => request('GET', `/api/player/orders/${orderNo}`),
  inventory: () => request('GET', '/api/player/inventory'),
  ledger: () => request('GET', '/api/player/ledger'),
  holds: () => request('GET', '/api/player/holds'),

  opItems: () => request('GET', '/api/operator/items'),
  opRecipes: (allVersions = true) => request('GET', `/api/operator/recipes?allVersions=${allVersions}`),
  opCreateDraft: (draft) => request('POST', '/api/operator/recipes/drafts', draft, { retry: 0 }),
  opPublish: (versionId) => request('POST', `/api/operator/recipes/versions/${versionId}/publish`, {}, { retry: 0 }),
  opOrders: () => request('GET', '/api/operator/orders'),
  opRevoke: (orderNo) => request('POST', `/api/operator/orders/${orderNo}/revoke`, {}, { retry: 0 }),
  opExceptions: (all = false) => request('GET', `/api/operator/exceptions?all=${all}`),
  opResolve: (exceptionNo, remark) =>
    request('POST', `/api/operator/exceptions/${exceptionNo}/resolve`, { remark }, { retry: 0 })
}
