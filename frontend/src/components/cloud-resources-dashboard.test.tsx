import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ResourcesPage from "@/app/resources/page";
import type { CloudAccount } from "@/lib/cloud-accounts";
import type { CloudResource, PageResponse } from "@/lib/cloud-resources";

const accounts: CloudAccount[] = [{
  id: 7, name: "Production AWS", provider: "AWS", externalAccountId: "123456789012",
  environment: "PRODUCTION", region: "eu-west-1", active: true,
  createdAt: "2026-09-24T12:00:00Z", updatedAt: "2026-09-24T12:00:00Z",
}];
const resource: CloudResource = {
  id: 42, cloudAccountId: 7, cloudAccountName: "Production AWS", provider: "AWS",
  externalResourceId: "arn:aws:ec2:eu-west-1:123456789012:instance/AbC",
  name: "Web server", category: "COMPUTE", providerService: "EC2", region: "eu-west-1", status: "ACTIVE",
  createdAt: "2026-09-24T12:00:00Z", updatedAt: "2026-09-24T13:45:00Z",
};
const page: PageResponse<CloudResource> = {
  content: [resource,
    { ...resource, id: 43, name: "Blob storage", provider: "AZURE", category: "STORAGE", status: "INACTIVE" },
    { ...resource, id: 44, name: "Cloud function", provider: "GCP", category: "SERVERLESS", status: "UNKNOWN" }],
  page: 0, size: 20, totalElements: 41, totalPages: 3,
};
const emptyPage = { ...page, content: [], totalElements: 0, totalPages: 0 };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { "Content-Type": "application/json" },
});
const failure = (message: string, errors = {}) => json({ timestamp: "2026-09-24T12:00:00Z", status: 500, message, errors }, 500);

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

describe("Cloud resource inventory", () => {
  const fetchMock = vi.fn<typeof fetch>();
  let resourceReply: (url: URL) => Promise<Response>;
  let accountReply: () => Promise<Response>;

  beforeEach(() => {
    fetchMock.mockReset();
    resourceReply = async (url) => json({ ...page, page: Number(url.searchParams.get("page") ?? 0) });
    accountReply = async () => json(accounts);
    fetchMock.mockImplementation((input) => {
      const url = new URL(String(input));
      return url.pathname.endsWith("/cloud-accounts") ? accountReply() : resourceReply(url);
    });
    vi.stubGlobal("fetch", fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  function queries() {
    return fetchMock.mock.calls.map(([url]) => new URL(String(url)))
      .filter((url) => url.pathname.endsWith("/cloud-resources"))
      .map((url) => Object.fromEntries(url.searchParams));
  }

  async function ready() {
    await screen.findByRole("table");
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Cloud account" })).toBeEnabled());
  }

  it("shows initial loading and disables unavailable actions", async () => {
    const pendingResources = deferred<Response>();
    const pendingAccounts = deferred<Response>();
    resourceReply = () => pendingResources.promise;
    accountReply = () => pendingAccounts.promise;
    render(<ResourcesPage />);
    expect(screen.getByText("Loading cloud resources…")).toHaveAttribute("role", "status");
    expect(screen.getByText("Loading cloud accounts…")).toHaveAttribute("role", "status");
    expect(screen.getByRole("combobox", { name: "Cloud account" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    await act(async () => { pendingResources.resolve(json(page)); pendingAccounts.resolve(json(accounts)); });
    await ready();
  });

  it("renders the semantic table, backend total, readable badges and UTC timestamp", async () => {
    render(<ResourcesPage />);
    await ready();
    expect(screen.getByRole("heading", { level: 1, name: "Cloud resources" })).toBeInTheDocument();
    expect(screen.getByText(/resources matching current filters/)).toHaveTextContent("41 resources");
    const table = within(screen.getByRole("table"));
    expect(table.getAllByRole("columnheader")).toHaveLength(8);
    expect(table.getByRole("rowheader", { name: /Web server/ })).toHaveTextContent(resource.externalResourceId);
    expect(table.getAllByText("Production AWS")).toHaveLength(3);
    for (const label of ["AWS", "Azure", "Google Cloud", "Compute", "Active", "Inactive", "Unknown"]) {
      expect(table.getByText(label)).toBeInTheDocument();
    }
    expect(table.getAllByText("EC2")).toHaveLength(3);
    expect(table.getAllByText("eu-west-1")).toHaveLength(3);
    expect(table.getAllByText("2026-09-24 13:45:00 UTC")[0]).toHaveAttribute("datetime", resource.updatedAt);
    expect(screen.getByText("Page 1 of 3")).toBeInTheDocument();
    expect(queries()).toEqual([{ page: "0", size: "20", sort: "createdAt,desc" }]);
  });

  it("shows empty inventory with unavailable pagination", async () => {
    resourceReply = async () => json(emptyPage);
    render(<ResourcesPage />);
    expect(await screen.findByText("No cloud resources yet")).toBeInTheDocument();
    expect(screen.queryByText("No matching resources")).not.toBeInTheDocument();
    expect(screen.getByText(/resources matching current filters/)).toHaveTextContent("0 resources");
    expect(screen.getByText("No pages")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
  });

  it("distinguishes filtered empty results and resets filters", async () => {
    resourceReply = async (url) => json(url.searchParams.has("provider") ? emptyPage : page);
    render(<ResourcesPage />);
    await ready();
    fireEvent.change(screen.getByLabelText("Provider"), { target: { value: "AZURE" } });
    expect(await screen.findByText("No matching resources")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Reset filters" }));
    await screen.findByRole("table");
    expect(screen.getByLabelText("Provider")).toHaveValue("");
    expect(queries().at(-1)).toEqual({ page: "0", size: "20", sort: "createdAt,desc" });
  });

  it("shows API errors and field messages, and retries the same filtered query", async () => {
    render(<ResourcesPage />);
    await ready();
    resourceReply = async () => failure("Inventory temporarily unavailable", { region: "Invalid region" });
    fireEvent.change(screen.getByLabelText("Region"), { target: { value: "global" } });
    expect(await screen.findByRole("alert")).toHaveTextContent("Inventory temporarily unavailable");
    expect(screen.getByRole("alert")).toHaveTextContent("region: Invalid region");
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.getByText("Resource total unavailable")).toBeInTheDocument();
    const failedQuery = queries().at(-1);
    resourceReply = async () => json(page);
    fireEvent.click(screen.getByRole("button", { name: "Retry resources" }));
    await screen.findByRole("table");
    expect(queries().at(-1)).toEqual(failedQuery);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("loads resources despite an account-filter error and retries accounts independently", async () => {
    accountReply = async () => failure("Accounts unavailable");
    render(<ResourcesPage />);
    await screen.findByRole("table");
    expect(await screen.findByRole("alert")).toHaveTextContent("Cloud account filter unavailable: Accounts unavailable");
    expect(screen.getByLabelText("Cloud account")).toBeDisabled();
    expect(screen.getByLabelText("Provider")).toBeEnabled();
    accountReply = async () => json(accounts);
    fireEvent.click(screen.getByRole("button", { name: "Retry cloud accounts" }));
    await ready();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(queries()).toHaveLength(1);
  });

  it("explains when no cloud accounts are available", async () => {
    accountReply = async () => json([]);
    render(<ResourcesPage />);
    expect(await screen.findByText("No cloud accounts available to filter.")).toBeInTheDocument();
    expect(screen.getByLabelText("Cloud account")).toHaveAccessibleDescription("No cloud accounts available to filter.");
    await screen.findByRole("table");
  });

  it.each([
    ["Cloud account", "7", "cloudAccountId"], ["Provider", "AZURE", "provider"],
    ["Category", "STORAGE", "category"], ["Region", "global", "region"], ["Status", "INACTIVE", "status"],
    ["Sort by", "name,asc", "sort"],
  ])("changing %s resets pagination and sends the selected value", async (label, value, key) => {
    render(<ResourcesPage />);
    await ready();
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await screen.findByText("Page 2 of 3");
    fireEvent.change(screen.getByLabelText(label), { target: { value } });
    await screen.findByText("Page 1 of 3");
    expect(queries().at(-1)).toEqual({ page: "0", size: "20", sort: "createdAt,desc", [key]: value });
  });

  it("preserves combined filters and sorting on Next and Previous and disables the final Next", async () => {
    render(<ResourcesPage />);
    await ready();
    for (const [label, value] of [["Cloud account", "7"], ["Provider", "AWS"], ["Category", "COMPUTE"],
      ["Region", "eu-west-1"], ["Status", "ACTIVE"], ["Sort by", "updatedAt,desc"]]) {
      fireEvent.change(screen.getByLabelText(label), { target: { value } });
      await screen.findByRole("table");
    }
    const filters = queries().at(-1);
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await screen.findByText("Page 2 of 3");
    expect(queries().at(-1)).toEqual({ ...filters, page: "1" });
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await screen.findByText("Page 3 of 3");
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Previous" }));
    await screen.findByText("Page 2 of 3");
    expect(queries().at(-1)).toEqual({ ...filters, page: "1" });
    fireEvent.click(screen.getByRole("button", { name: "Reset filters" }));
    await screen.findByText("Page 1 of 3");
    expect(queries().at(-1)).toEqual({ page: "0", size: "20", sort: "createdAt,desc" });
    for (const label of ["Cloud account", "Provider", "Category", "Region", "Status"]) {
      expect(screen.getByLabelText(label)).toHaveValue("");
    }
    expect(screen.getByLabelText("Sort by")).toHaveValue("createdAt,desc");
  });

  it("omits a filter that is cleared individually", async () => {
    render(<ResourcesPage />);
    await ready();
    fireEvent.change(screen.getByLabelText("Provider"), { target: { value: "AWS" } });
    await screen.findByRole("table");
    fireEvent.change(screen.getByLabelText("Provider"), { target: { value: "" } });
    await screen.findByRole("table");
    expect(queries().at(-1)).not.toHaveProperty("provider");
  });

  it.each(["success", "failure"])("ignores a late %s from an older request", async (outcome) => {
    const older = deferred<Response>();
    resourceReply = (url) => url.searchParams.has("provider")
      ? Promise.resolve(json({ ...page, content: [{ ...resource, name: "Latest result" }], totalElements: 1, totalPages: 1 }))
      : older.promise;
    render(<ResourcesPage />);
    fireEvent.change(screen.getByLabelText("Provider"), { target: { value: "AWS" } });
    expect(await screen.findByText("Latest result")).toBeInTheDocument();
    await act(async () => {
      if (outcome === "success") older.resolve(json(page));
      else older.reject(new Error("Old request failed"));
    });
    expect(screen.getByText("Latest result")).toBeInTheDocument();
    expect(screen.queryByText("Web server")).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByText(/resources matching current filters/)).toHaveTextContent("1 resources");
  });

  it("hides old rows and totals immediately while filters are loading", async () => {
    render(<ResourcesPage />);
    await ready();
    const pending = deferred<Response>();
    resourceReply = () => pending.promise;
    fireEvent.change(screen.getByLabelText("Category"), { target: { value: "STORAGE" } });
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByText(/41 resources/)).not.toBeInTheDocument();
    expect(screen.getByText("Loading resource total…")).toBeInTheDocument();
    await act(async () => pending.resolve(json(emptyPage)));
    expect(await screen.findByText("No matching resources")).toBeInTheDocument();
  });

  it("provides accessible filter, results, pagination and scroll-region labels", async () => {
    render(<ResourcesPage />);
    await ready();
    expect(screen.getByRole("region", { name: "Resource filters" })).toBeInTheDocument();
    for (const label of ["Cloud account", "Provider", "Category", "Status", "Sort by"]) {
      expect(screen.getByRole("combobox", { name: label })).toBeInTheDocument();
    }
    expect(screen.getByRole("textbox", { name: "Region" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Resource results" })).toHaveAttribute("aria-busy", "false");
    expect(screen.getByRole("region", { name: "Cloud resource inventory table" })).toHaveAttribute("tabindex", "0");
    expect(within(screen.getByRole("navigation", { name: "Resource pagination" })).getAllByRole("button")).toHaveLength(2);
    expect(screen.queryByRole("button", { name: /create|update|delete|details/i })).not.toBeInTheDocument();
  });

  it("keeps Previous available if a later page becomes empty", async () => {
    resourceReply = async (url) => json(url.searchParams.get("page") === "1"
      ? { ...page, page: 1, content: [], totalElements: 1, totalPages: 1 } : page);
    render(<ResourcesPage />);
    await ready();
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(await screen.findByText("No resources on this page")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Previous" })).toBeEnabled();
    fireEvent.click(screen.getByRole("button", { name: "Previous" }));
    await screen.findByRole("table");
  });
});
