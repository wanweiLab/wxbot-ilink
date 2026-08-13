// Copyright 2026 wxbot-ilink contributors
// SPDX-License-Identifier: Apache-2.0

export interface BotRegistration {
  userId: string;
  clientKey: string;
  displayName: string;
  status: BotStatus;
  lastError?: string;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export type BotStatus =
  | 'LOGIN_REQUIRED'
  | 'LOGIN_PENDING'
  | 'ONLINE'
  | 'OFFLINE'
  | 'ERROR'
  | 'DELETING';

/** 管理端可观察的二维码登录阶段。 */
export type BotLoginPhase =
  | 'WAITING'
  | 'WAITING_SCAN'
  | 'SCANNED'
  | 'CONFIRMED'
  | 'BINDING'
  | 'BOUND'
  | 'EXPIRED'
  | 'FAILED';

export interface BotRuntimeView {
  registration: BotRegistration;
  running: boolean;
  health?: {
    state: string;
    capturedAt: string;
    consecutivePollFailures: number;
    inboxBacklog: number;
    dispatcherQueuedMessages: number;
  };
}

export interface LoginResponse {
  attemptId: string;
  imageContent: string;
  expiresAt: string;
  phase: BotLoginPhase;
}

/** 二维码登录尝试的最新状态，不包含微信会话令牌。 */
export interface LoginStatusResponse {
  attemptId: string;
  phase: BotLoginPhase;
  message: string | null;
  expiresAt: string;
  createdAt: string;
  updatedAt: string;
  version: number;
  registrationStatus: BotStatus;
  wechatUserId: string | null;
  botId: string | null;
}

/** 统一封装 Bearer 会话和安全错误读取。 */
async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = sessionStorage.getItem('wxbot-admin-token');
  const response = await fetch(path, {
    ...init,
    headers: {
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init.headers,
    },
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({ message: `请求失败：${response.status}` }));
    if (response.status === 401 && path !== '/api/auth/login') {
      sessionStorage.removeItem('wxbot-admin-token');
      window.dispatchEvent(new Event('wxbot-session-expired'));
    }
    throw new Error(error.message || '请求失败');
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export const api = {
  async login(username: string, password: string) {
    const session = await request<{ token: string; expiresAt: string }>('/api/auth/login', {
      method: 'POST', body: JSON.stringify({ username, password }),
    });
    sessionStorage.setItem('wxbot-admin-token', session.token);
    return session;
  },
  logout: () => request<void>('/api/auth/logout', { method: 'POST' }),
  listBots: () => request<BotRuntimeView[]>('/api/users/bots'),
  bindBot: (userId: string, displayName: string) => request<BotRegistration>(
    `/api/users/${encodeURIComponent(userId)}/bot`,
    { method: 'POST', body: JSON.stringify({ displayName }) },
  ),
  loginBot: (userId: string) => request<LoginResponse>(
    `/api/users/${encodeURIComponent(userId)}/bot/login`, { method: 'POST' },
  ),
  getLoginStatus: (userId: string, attemptId: string, signal?: AbortSignal) => request<LoginStatusResponse>(
    `/api/users/${encodeURIComponent(userId)}/bot/login/${encodeURIComponent(attemptId)}`,
    { signal },
  ),
  restoreBot: (userId: string) => request<{ restored: boolean }>(
    `/api/users/${encodeURIComponent(userId)}/bot/restore`, { method: 'POST' },
  ),
  stopBot: (userId: string) => request<void>(
    `/api/users/${encodeURIComponent(userId)}/bot/stop`, { method: 'POST' },
  ),
  unbindBot: (userId: string) => request<void>(
    `/api/users/${encodeURIComponent(userId)}/bot`, { method: 'DELETE' },
  ),
  sendTestMessage: (userId: string, text: string) => request(
    `/api/users/${encodeURIComponent(userId)}/bot/messages/test`,
    { method: 'POST', body: JSON.stringify({ text }) },
  ),
};
