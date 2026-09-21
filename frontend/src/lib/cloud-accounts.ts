export type CloudProvider = "AWS" | "AZURE" | "GCP";
export type CloudEnvironment = "DEVELOPMENT" | "STAGING" | "PRODUCTION";

export type CloudAccount = {
  id: number;
  name: string;
  provider: CloudProvider;
  externalAccountId: string;
  environment: CloudEnvironment;
  region: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
};

export type CreateCloudAccount = Pick<CloudAccount,
  "name" | "provider" | "externalAccountId" | "environment" | "region">;

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_URL}${path}`, {
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new Error(problem?.message ?? `Request failed with status ${response.status}`);
  }
  return response.json();
}

export const cloudAccountApi = {
  list: () => request<CloudAccount[]>("/api/v1/cloud-accounts"),
  create: (account: CreateCloudAccount) => request<CloudAccount>("/api/v1/cloud-accounts", {
    method: "POST", body: JSON.stringify(account),
  }),
  changeStatus: (id: number, active: boolean) =>
    request<CloudAccount>(`/api/v1/cloud-accounts/${id}/status?active=${active}`, { method: "PATCH" }),
};
