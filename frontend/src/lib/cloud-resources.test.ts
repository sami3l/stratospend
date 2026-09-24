import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ApiErrorResponse } from "./api-error";
import type {
  CloudResource, CloudResourceCreateInput, CloudResourceQuery, CloudResourceUpdateInput, PageResponse,
} from "./cloud-resources";

const BASE = "http://localhost:8080/api/v1/cloud-resources";
const resource: CloudResource = {
  id: 42, cloudAccountId: 7, cloudAccountName: "Production AWS", provider: "AWS",
  externalResourceId: " arn:aws:ec2:eu-west-1:123456789012:instance/AbC ",
  name: "Web server", category: "COMPUTE", providerService: "EC2", region: "eu-west-1", status: "ACTIVE",
  createdAt: "2026-09-24T12:00:00Z", updatedAt: "2026-09-24T12:00:00Z",
};
const input: CloudResourceCreateInput = {
  cloudAccountId: resource.cloudAccountId, externalResourceId: resource.externalResourceId,
  name: resource.name, category: resource.category, providerService: resource.providerService,
  region: resource.region, status: resource.status,
};
const update: CloudResourceUpdateInput = {
  name: "Updated server", category: "CONTAINER", providerService: "ECS", region: "global", status: "INACTIVE",
};
const page: PageResponse<CloudResource> = {
  content: [resource], page: 0, size: 20, totalElements: 1, totalPages: 1,
};

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("cloud resource API", () => {
  const fetchMock = vi.fn<typeof fetch>();
  let api: typeof import("./cloud-resources");
  let ApiError: typeof import("./api-error").ApiError;

  beforeEach(async () => {
    vi.resetModules();
    vi.stubEnv("NEXT_PUBLIC_API_URL", undefined);
    fetchMock.mockReset();
    vi.stubGlobal("fetch", fetchMock);
    api = await import("./cloud-resources");
    ({ ApiError } = await import("./api-error"));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it("lists resources with server defaults and preserves the stable page contract", async () => {
    fetchMock.mockResolvedValue(jsonResponse(page));
    await expect(api.listCloudResources()).resolves.toEqual(page);
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith(BASE, undefined);
  });

  it("uses the configured API URL, including an optional base path and trailing slash", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_URL", "https://api.stratospend.test/backend/");
    vi.resetModules();
    const configuredApi = await import("./cloud-resources");
    fetchMock.mockResolvedValue(jsonResponse(page));
    await configuredApi.listCloudResources();
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith(
      "https://api.stratospend.test/backend/api/v1/cloud-resources", undefined,
    );
  });

  it("serializes every supported query parameter", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ ...page, page: 2, size: 5, totalElements: 11, totalPages: 3 }));
    const query: CloudResourceQuery = {
      cloudAccountId: 7, provider: "AWS", category: "COMPUTE", region: "eu-west-1", status: "ACTIVE",
      page: 2, size: 5, sort: "name,asc",
    };
    await expect(api.listCloudResources(query)).resolves.toEqual({
      ...page, page: 2, size: 5, totalElements: 11, totalPages: 3,
    });
    const url = new URL(String(fetchMock.mock.calls[0][0]));
    expect(Object.fromEntries(url.searchParams)).toEqual({
      cloudAccountId: "7", provider: "AWS", category: "COMPUTE", region: "eu-west-1", status: "ACTIVE",
      page: "2", size: "5", sort: "name,asc",
    });
    expect(url.search).toContain("sort=name%2Casc");
  });

  it("escapes query values without injecting additional parameters", async () => {
    fetchMock.mockResolvedValue(jsonResponse(page));
    await api.listCloudResources({ region: "west & status=INACTIVE/#?" });
    const url = new URL(String(fetchMock.mock.calls[0][0]));
    expect([...url.searchParams.entries()]).toEqual([["region", "west & status=INACTIVE/#?"]]);
    expect(url.hash).toBe("");
  });

  it("omits undefined and empty optional filters without adding a dangling question mark", async () => {
    fetchMock.mockResolvedValue(jsonResponse(page));
    await api.listCloudResources({
      cloudAccountId: undefined, provider: "", category: "", region: "", status: "",
      page: undefined, size: undefined, sort: "",
    });
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith(BASE, undefined);
  });

  it("preserves page zero alongside other supplied parameters", async () => {
    fetchMock.mockResolvedValue(jsonResponse(page));
    await api.listCloudResources({ page: 0, size: 10, region: "global" });
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith(`${BASE}?page=0&size=10&region=global`, undefined);
  });

  it("gets one resource", async () => {
    fetchMock.mockResolvedValue(jsonResponse(resource));
    await expect(api.getCloudResource(42)).resolves.toEqual(resource);
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith(`${BASE}/42`, undefined);
  });

  it("creates a resource with JSON and preserves the external identifier exactly", async () => {
    fetchMock.mockResolvedValue(jsonResponse(resource, 201));
    await expect(api.createCloudResource(input)).resolves.toEqual(resource);
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith(BASE, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(input),
    });
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body)).externalResourceId).toBe(resource.externalResourceId);
  });

  it("updates only the editable fields using PUT", async () => {
    const updated = { ...resource, ...update, updatedAt: "2026-09-24T13:00:00Z" };
    fetchMock.mockResolvedValue(jsonResponse(updated));
    await expect(api.updateCloudResource(42, update)).resolves.toEqual(updated);
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith(`${BASE}/42`, {
      method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(update),
    });
    expect(Object.keys(JSON.parse(String(fetchMock.mock.calls[0][1]?.body)))).toEqual([
      "name", "category", "providerService", "region", "status",
    ]);
  });

  it("returns a successful empty page with its metadata intact", async () => {
    const empty: PageResponse<CloudResource> = { content: [], page: 3, size: 10, totalElements: 0, totalPages: 0 };
    fetchMock.mockResolvedValue(jsonResponse(empty));
    await expect(api.listCloudResources({ page: 3, size: 10 })).resolves.toEqual(empty);
  });

  it.each([
    [400, "Validation failed", { name: "must not be blank", region: "must contain only lowercase letters, digits or hyphens" }],
    [404, "Cloud resource not found: 42", {}],
    [409, "A resource with external ID AbC already exists in cloud account 7", {}],
    [500, "Internal server error", {}],
  ])("preserves the backend error contract for HTTP %s", async (status, message, errors) => {
    const problem: ApiErrorResponse = { timestamp: "2026-09-24T12:00:00Z", status, message, errors };
    fetchMock.mockResolvedValue(jsonResponse(problem, status));
    const pending = status === 400 ? api.updateCloudResource(42, update)
      : status === 404 ? api.getCloudResource(42)
      : status === 409 ? api.createCloudResource(input) : api.listCloudResources();
    await expect(pending).rejects.toBeInstanceOf(ApiError);
    await expect(pending).rejects.toMatchObject({ name: "ApiError", ...problem });
  });

  it.each(["", "<html>Bad Gateway</html>", "{invalid json"])("handles an empty or non-JSON error body: %j", async (body) => {
    fetchMock.mockResolvedValue(new Response(body, { status: 502 }));
    await expect(api.listCloudResources()).rejects.toMatchObject({
      name: "ApiError", status: 502, message: "Request failed with status 502", errors: {}, timestamp: undefined,
    });
  });

  it.each([null, [], "invalid", 42, {}, { message: " ", errors: [] }, { message: 123, errors: null }])(
    "handles an invalid JSON error shape: %j", async (body) => {
      fetchMock.mockResolvedValue(jsonResponse(body, 500));
      await expect(api.getCloudResource(42)).rejects.toMatchObject({
        name: "ApiError", status: 500, message: "Request failed with status 500", errors: {},
      });
    },
  );

  it("keeps useful parts of an incomplete error while ignoring invalid field messages", async () => {
    fetchMock.mockResolvedValue(jsonResponse({
      status: 200, timestamp: 123, message: "Validation failed",
      errors: { name: "must not be blank", category: ["invalid"], region: null },
    }, 400));
    await expect(api.createCloudResource(input)).rejects.toMatchObject({
      status: 400, timestamp: undefined, message: "Validation failed", errors: { name: "must not be blank" },
    });
  });

  it("propagates network failure instead of returning empty data", async () => {
    const failure = new TypeError("Failed to fetch");
    fetchMock.mockRejectedValue(failure);
    await expect(api.listCloudResources()).rejects.toBe(failure);
  });

  it("rejects invalid success JSON instead of silently returning an empty page", async () => {
    fetchMock.mockResolvedValue(new Response("invalid json", { status: 200 }));
    await expect(api.listCloudResources()).rejects.toBeInstanceOf(SyntaxError);
  });
});
