import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ResourceDetailsPage from "@/app/resources/[id]/page";
import { CloudResourceDetails } from "./cloud-resource-details";
import type { CloudResource } from "@/lib/cloud-resources";

const resource: CloudResource = {
  id: 42, cloudAccountId: 7, cloudAccountName: "Production AWS", provider: "AWS",
  externalResourceId: "arn:aws:ec2:eu-west-1:123456789012:instance/AbC",
  name: "Web server", category: "COMPUTE", providerService: "EC2", region: "eu-west-1", status: "ACTIVE",
  createdAt: "2026-09-24T12:00:00Z", updatedAt: "2026-09-24T13:45:00Z",
};
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { "Content-Type": "application/json" },
});
const problem = (status: number, message: string) =>
  json({ timestamp: "2026-09-24T12:00:00Z", status, message, errors: {} }, status);

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => { resolve = res; });
  return { promise, resolve };
}

describe("Cloud resource details", () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    fetchMock.mockReset();
    fetchMock.mockImplementation(async () => json(resource));
    vi.stubGlobal("fetch", fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  function requestedPaths() {
    return fetchMock.mock.calls.map(([url]) => new URL(String(url)).pathname);
  }

  function detail(term: string) {
    const label = screen.getByText(term, { selector: "dt" });
    return within(label.parentElement as HTMLElement).getByRole("definition");
  }

  it("shows loading, then every resource field", async () => {
    const pending = deferred<Response>();
    fetchMock.mockImplementation(() => pending.promise);
    render(<CloudResourceDetails id="42" />);
    expect(screen.getByText("Loading cloud resource…")).toHaveAttribute("role", "status");
    expect(screen.getByRole("heading", { level: 1, name: "Resource details" })).toBeInTheDocument();

    await act(async () => pending.resolve(json(resource)));
    expect(await screen.findByRole("heading", { level: 1, name: "Web server" })).toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(detail("External resource ID")).toHaveTextContent(resource.externalResourceId);
    expect(detail("Cloud account")).toHaveTextContent("Production AWS");
    expect(detail("Provider")).toHaveTextContent("AWS");
    expect(detail("Category")).toHaveTextContent("Compute");
    expect(detail("Provider service")).toHaveTextContent("EC2");
    expect(detail("Region")).toHaveTextContent("eu-west-1");
    expect(detail("Status")).toHaveTextContent("Active");
    expect(within(detail("Created (UTC)")).getByText("2026-09-24 12:00:00 UTC"))
      .toHaveAttribute("datetime", resource.createdAt);
    expect(within(detail("Last updated (UTC)")).getByText("2026-09-24 13:45:00 UTC"))
      .toHaveAttribute("datetime", resource.updatedAt);
    expect(requestedPaths()).toEqual(["/api/v1/cloud-resources/42"]);
  });

  it("links back to the resource inventory", async () => {
    render(<CloudResourceDetails id="42" />);
    await screen.findByRole("heading", { level: 1, name: "Web server" });
    expect(screen.getByRole("link", { name: "← Back to resources" })).toHaveAttribute("href", "/resources");
  });

  it("reads the resource ID from the route parameters", async () => {
    render(await ResourceDetailsPage({ params: Promise.resolve({ id: "42" }) }));
    expect(await screen.findByRole("heading", { level: 1, name: "Web server" })).toBeInTheDocument();
    expect(requestedPaths()).toEqual(["/api/v1/cloud-resources/42"]);
  });

  it.each(["abc", "0", "-1", "1.5", "007", "1e3", "", "99999999999999999999"])(
    "shows not found without a request for invalid ID %j", (id) => {
      render(<CloudResourceDetails id={id} />);
      expect(screen.getByRole("heading", { level: 1, name: "Resource not found" })).toBeInTheDocument();
      expect(screen.getByRole("link", { name: "resource inventory" })).toHaveAttribute("href", "/resources");
      expect(fetchMock).not.toHaveBeenCalled();
    });

  it("shows not found without a retry when the API returns 404", async () => {
    fetchMock.mockImplementation(async () => problem(404, "Cloud resource 42 was not found"));
    render(<CloudResourceDetails id="42" />);
    expect(await screen.findByRole("heading", { level: 1, name: "Resource not found" })).toBeInTheDocument();
    expect(screen.getByText("No resource exists with this ID")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("shows other API errors and recovers after a retry", async () => {
    fetchMock.mockImplementationOnce(async () => problem(500, "Database unavailable"));
    render(<CloudResourceDetails id="42" />);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Database unavailable");
    expect(screen.getByRole("heading", { level: 1, name: "Resource details" })).toBeInTheDocument();

    fireEvent.click(within(alert).getByRole("button", { name: "Retry" }));
    expect(screen.getByText("Loading cloud resource…")).toBeInTheDocument();
    expect(await screen.findByRole("heading", { level: 1, name: "Web server" })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(requestedPaths()).toEqual(["/api/v1/cloud-resources/42", "/api/v1/cloud-resources/42"]);
  });

  it("shows a fallback message when the network request fails", async () => {
    fetchMock.mockImplementation(async () => { throw new TypeError("Failed to fetch"); });
    render(<CloudResourceDetails id="42" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Failed to fetch");
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
  });

  it("ignores a late response for a previous resource ID", async () => {
    const first = deferred<Response>();
    fetchMock.mockImplementationOnce(() => first.promise);
    const { rerender } = render(<CloudResourceDetails id="41" />);
    rerender(<CloudResourceDetails id="42" />);
    expect(await screen.findByRole("heading", { level: 1, name: "Web server" })).toBeInTheDocument();
    await act(async () => first.resolve(json({ ...resource, id: 41, name: "Old server" })));
    expect(screen.queryByText("Old server")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: "Web server" })).toBeInTheDocument();
  });
});
