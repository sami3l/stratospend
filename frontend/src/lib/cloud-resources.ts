import { ApiError } from "./api-error";
import type { CloudProvider } from "./cloud-accounts";

export type ResourceCategory =
  | "COMPUTE" | "STORAGE" | "DATABASE" | "NETWORK" | "CONTAINER" | "SERVERLESS" | "OTHER";
export type ResourceStatus = "ACTIVE" | "INACTIVE" | "UNKNOWN";

export type CloudResource = {
  id: number;
  cloudAccountId: number;
  cloudAccountName: string;
  provider: CloudProvider;
  externalResourceId: string;
  name: string;
  category: ResourceCategory;
  providerService: string;
  region: string;
  status: ResourceStatus;
  createdAt: string;
  updatedAt: string;
};

export type CloudResourceUpdateInput = Pick<CloudResource,
  "name" | "category" | "providerService" | "region" | "status">;

export type CloudResourceCreateInput = CloudResourceUpdateInput & Pick<CloudResource,
  "cloudAccountId" | "externalResourceId">;

type SortField = "id" | "name" | "category" | "providerService" | "region" | "status" | "createdAt" | "updatedAt";

export type CloudResourceQuery = {
  cloudAccountId?: number;
  provider?: CloudProvider | "";
  category?: ResourceCategory | "";
  region?: string;
  status?: ResourceStatus | "";
  page?: number;
  size?: number;
  sort?: `${SortField},${"asc" | "desc"}` | "";
};

export type PageResponse<T> = {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

const API_URL = (process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080").replace(/\/+$/, "");
const RESOURCE_PATH = "/api/v1/cloud-resources";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_URL}${path}`, init);
  if (!response.ok) {
    const problem: unknown = await response.json().catch(() => null);
    throw new ApiError(response.status, problem);
  }
  return response.json();
}

export function listCloudResources(query: CloudResourceQuery = {}): Promise<PageResponse<CloudResource>> {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== "") {
      params.set(key, String(value));
    }
  }
  const search = params.toString();
  return request(`${RESOURCE_PATH}${search ? `?${search}` : ""}`);
}

export function getCloudResource(id: number): Promise<CloudResource> {
  return request(`${RESOURCE_PATH}/${id}`);
}

export function createCloudResource(input: CloudResourceCreateInput): Promise<CloudResource> {
  return request(RESOURCE_PATH, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
}

export function updateCloudResource(id: number, input: CloudResourceUpdateInput): Promise<CloudResource> {
  return request(`${RESOURCE_PATH}/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
}
