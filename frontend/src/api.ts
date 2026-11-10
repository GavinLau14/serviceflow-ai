export interface User {
  id: number;
  username: string;
  displayName: string;
  role: "CUSTOMER" | "AGENT" | "SUPERVISOR" | "ADMIN";
  teamId: number | null;
}
export type Status =
  | "OPEN"
  | "ASSIGNED"
  | "IN_PROGRESS"
  | "WAITING_CUSTOMER"
  | "CLOSED";
export interface Ticket {
  id: number;
  reference: string;
  title: string;
  description: string;
  category: string;
  priority: string;
  status: Status;
  customerId: number;
  teamId: number;
  assigneeId: number | null;
  version: number;
  createdAt: string;
  updatedAt: string;
  customerName: string;
  assigneeName: string | null;
}
export interface Comment {
  id: number;
  authorId: number;
  authorName: string;
  body: string;
  internal: boolean;
  createdAt: string;
}
export interface TicketEvent {
  id: number;
  actorName: string;
  action: string;
  fromStatus: Status | null;
  toStatus: Status;
  message: string;
  createdAt: string;
}
export interface Detail {
  ticket: Ticket;
  comments: Comment[];
  events: TicketEvent[];
  allowedActions: string[];
}
export interface TicketPage {
  items: Ticket[];
  total: number;
  page: number;
  size: number;
}
export const statusNames: Record<Status, string> = {
  OPEN: "待分派",
  ASSIGNED: "已分派",
  IN_PROGRESS: "处理中",
  WAITING_CUSTOMER: "等待客户确认",
  CLOSED: "已关闭",
};
export const priorityNames: Record<string, string> = {
  LOW: "低",
  NORMAL: "普通",
  HIGH: "高",
  URGENT: "紧急",
};
export const roleNames: Record<string, string> = {
  CUSTOMER: "客户",
  AGENT: "客服",
  SUPERVISOR: "主管",
  ADMIN: "管理员",
};
export function time(s: string) {
  return new Date(s + "Z").toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}
export async function api<T>(path: string, body?: unknown): Promise<T> {
  const token = sessionStorage.getItem("sf_token");
  const response = await fetch("/api" + path, {
    method: body === undefined ? "GET" : "POST",
    headers: {
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: "Bearer " + token } : {}),
    },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  });
  if (response.status === 204) return undefined as T;
  const data = await response
    .json()
    .catch(() => ({ message: "服务返回异常，请稍后重试" }));
  if (!response.ok) {
    if (response.status === 401)
      window.dispatchEvent(new Event("sf-session-expired"));
    throw new ApiError(response.status, data.message ?? "请求失败");
  }
  return data as T;
}
