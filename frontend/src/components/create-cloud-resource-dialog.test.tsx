import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import ResourcesPage from "@/app/resources/page";
import type { CloudAccount } from "@/lib/cloud-accounts";
import type { CloudResource, CloudResourceCreateInput } from "@/lib/cloud-resources";

const account: CloudAccount = {
  id: 7, name: "Production AWS", provider: "AWS", externalAccountId: "123456789012",
  environment: "PRODUCTION", region: "eu-west-1", active: true,
  createdAt: "2026-09-24T12:00:00Z", updatedAt: "2026-09-24T12:00:00Z",
};
const input: CloudResourceCreateInput = {
  cloudAccountId: 7, externalResourceId: "  arn:aws:ec2:eu-west-1:123456789012:instance/AbC  ",
  name: "Web server", category: "COMPUTE", providerService: "EC2", region: "global", status: "ACTIVE",
};
const created: CloudResource = {
  ...input, id: 42, cloudAccountName: account.name, provider: "AWS",
  createdAt: "2026-09-24T12:00:00Z", updatedAt: "2026-09-24T12:00:00Z",
};
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { "Content-Type": "application/json" },
});
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => { resolve = res; });
  return { promise, resolve };
}

// jsdom has no native modal implementation. Browser checks cover the top layer and keyboard focus trap.
const originalShowModal = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, "showModal");
const originalClose = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, "close");
beforeAll(() => {
  Object.defineProperty(HTMLDialogElement.prototype, "showModal", { configurable: true, value() { this.setAttribute("open", ""); } });
  Object.defineProperty(HTMLDialogElement.prototype, "close", { configurable: true, value() { this.removeAttribute("open"); } });
});
afterAll(() => {
  for (const [method, descriptor] of [["showModal", originalShowModal], ["close", originalClose]] as const) {
    if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, method, descriptor);
    else Reflect.deleteProperty(HTMLDialogElement.prototype, method);
  }
});

describe("Cloud resource creation", () => {
  const fetchMock = vi.fn<typeof fetch>();
  let accountReply: () => Promise<Response>;
  let createReply: () => Promise<Response>;
  let listReply: (url: URL) => Promise<Response>;

  beforeEach(() => {
    fetchMock.mockReset();
    accountReply = async () => json([account]);
    createReply = async () => json(created, 201);
    listReply = async (url) => json({ content: posts().length ? [created] : [], page: Number(url.searchParams.get("page")),
      size: 20, totalElements: posts().length ? 1 : 0, totalPages: posts().length ? 1 : 0 });
    fetchMock.mockImplementation((url, init) => {
      if (String(url).includes("cloud-accounts")) return accountReply();
      return init?.method === "POST" ? createReply() : listReply(new URL(String(url)));
    });
    vi.stubGlobal("fetch", fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  function posts() { return fetchMock.mock.calls.filter(([, init]) => init?.method === "POST"); }
  function listings() { return fetchMock.mock.calls.filter(([url, init]) => String(url).includes("cloud-resources") && !init?.method); }
  async function open() {
    const add = screen.getByRole("button", { name: "Add resource" });
    await waitFor(() => expect(add).toBeEnabled());
    add.focus();
    fireEvent.click(add);
    return screen.getByRole("dialog", { name: "Add cloud resource" });
  }
  function fill(dialog: HTMLElement) {
    const view = within(dialog);
    for (const [label, value] of [["Cloud account", "7"], ["External resource ID", input.externalResourceId],
      ["Name", input.name], ["Category", input.category], ["Provider service", input.providerService],
      ["Region", input.region], ["Status", input.status]]) {
      fireEvent.change(view.getByLabelText(label), { target: { value } });
    }
  }
  function submit(dialog: HTMLElement) { fireEvent.click(within(dialog).getByRole("button", { name: "Create resource" })); }
  function cancel(dialog: HTMLElement) { fireEvent(dialog, new Event("cancel", { bubbles: false, cancelable: true })); }

  it("opens an accessible dialog, focuses its first field and returns focus after Cancel", async () => {
    render(<ResourcesPage />);
    const dialog = await open();
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(within(dialog).getByLabelText("Cloud account")).toHaveFocus();
    expect(document.body.style.overflow).toBe("hidden");
    fill(dialog);
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add resource" })).toHaveFocus();
    expect(document.body.style.overflow).toBe("");
    expect(posts()).toHaveLength(0);
    const reopened = await open();
    expect(within(reopened).getByLabelText("Name")).toHaveValue("");
  });

  it("handles the native Escape cancel event without creating a resource", async () => {
    render(<ResourcesPage />);
    cancel(await open());
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add resource" })).toHaveFocus();
    expect(posts()).toHaveLength(0);
  });

  it("includes all required fields, category/status options and database length limits", async () => {
    render(<ResourcesPage />);
    const view = within(await open());
    for (const label of ["Cloud account", "External resource ID", "Name", "Category", "Provider service", "Region", "Status"]) {
      expect(view.getByLabelText(label)).toBeRequired();
    }
    expect(within(view.getByLabelText("Category")).getAllByRole("option").map((option) => option.getAttribute("value")))
      .toEqual(["", "COMPUTE", "STORAGE", "DATABASE", "NETWORK", "CONTAINER", "SERVERLESS", "OTHER"]);
    expect(within(view.getByLabelText("Status")).getAllByRole("option").map((option) => option.getAttribute("value")))
      .toEqual(["", "ACTIVE", "INACTIVE", "UNKNOWN"]);
    for (const [label, length] of [["External resource ID", 512], ["Name", 120], ["Provider service", 80], ["Region", 40]] as const) {
      expect(view.getByLabelText(label)).toHaveAttribute("maxlength", String(length));
    }
    expect(view.getByLabelText("Region")).toHaveAttribute("pattern", "[a-z0-9-]+");
  });

  it.each(["empty", "loading", "error"])("disables creation when accounts are %s and explains why", async (state) => {
    accountReply = state === "loading" ? () => new Promise(() => {})
      : state === "error" ? async () => json({ message: "Accounts unavailable" }, 500) : async () => json([]);
    render(<ResourcesPage />);
    if (state === "empty") await screen.findByText("No cloud accounts available to filter.");
    if (state === "error") await screen.findByRole("alert");
    const button = screen.getByRole("button", { name: "Add resource" });
    expect(button).toBeDisabled();
    expect(button).toHaveAccessibleDescription(state === "empty" ? "Add a cloud account first to create a resource."
      : state === "loading" ? "Loading cloud accounts before adding a resource." : "Retry cloud accounts below before adding a resource.");
    fireEvent.click(button);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });

  it("validates every required field locally and associates errors with the fields", async () => {
    render(<ResourcesPage />);
    const dialog = await open();
    submit(dialog);
    await waitFor(() => expect(within(dialog).getByLabelText("Cloud account")).toHaveAttribute("aria-invalid", "true"));
    for (const label of ["Cloud account", "External resource ID", "Name", "Category", "Provider service", "Region", "Status"]) {
      const field = within(dialog).getByLabelText(label);
      expect(field).toHaveAttribute("aria-invalid", "true");
      expect(field).toHaveAccessibleDescription();
    }
    expect(within(dialog).getByLabelText("Cloud account")).toHaveFocus();
    expect(posts()).toHaveLength(0);
  });

  it.each([["External resource ID", 512], ["Name", 120], ["Provider service", 80], ["Region", 40]] as const)(
    "rejects blank and oversized %s without submitting", async (label, limit) => {
      render(<ResourcesPage />);
      const dialog = await open();
      fill(dialog);
      const field = within(dialog).getByLabelText(label);
      for (const value of ["  ", "a".repeat(limit + 1)]) {
        fireEvent.change(field, { target: { value } });
        submit(dialog);
        await waitFor(() => expect(field).toHaveAttribute("aria-invalid", "true"));
        expect(field).toHaveAccessibleDescription(value.trim() ? `${label} must be ${limit} characters or fewer.` : `${label} is required.`);
      }
      expect(posts()).toHaveLength(0);
    },
  );

  it("rejects non-positive account IDs", async () => {
    accountReply = async () => json([{ ...account, id: 0 }]);
    render(<ResourcesPage />);
    const dialog = await open();
    fill(dialog);
    fireEvent.change(within(dialog).getByLabelText("Cloud account"), { target: { value: "0" } });
    submit(dialog);
    expect(await within(dialog).findByText("Select a cloud account with a positive ID.")).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });

  it("rejects invalid region syntax", async () => {
    render(<ResourcesPage />);
    const dialog = await open();
    fill(dialog);
    fireEvent.change(within(dialog).getByLabelText("Region"), { target: { value: "EU_west 1" } });
    submit(dialog);
    expect(await within(dialog).findByText("Use only lowercase letters, digits and hyphens, or global.")).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });

  it("sends the exact payload, closes, announces success and refreshes inventory", async () => {
    render(<ResourcesPage />);
    const dialog = await open();
    fill(dialog);
    submit(dialog);
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(posts()).toHaveLength(1);
    expect(posts()[0][1]).toEqual({ method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(input) });
    expect(screen.getByText("Resource “Web server” added. Current filters still apply.")).toHaveAttribute("role", "status");
    expect(await screen.findByRole("table")).toHaveTextContent("Web server");
    expect(listings()).toHaveLength(2);
    expect(screen.getByRole("button", { name: "Add resource" })).toHaveFocus();
  });

  it("preserves all active filters, sorting and page when refreshing after creation", async () => {
    listReply = async (url) => json({ content: [created], page: Number(url.searchParams.get("page")), size: 20, totalElements: 41, totalPages: 3 });
    render(<ResourcesPage />);
    await waitFor(() => expect(screen.getByRole("button", { name: "Add resource" })).toBeEnabled());
    for (const [label, value] of [["Cloud account", "7"], ["Provider", "AWS"], ["Category", "COMPUTE"],
      ["Region", "global"], ["Status", "ACTIVE"], ["Sort by", "name,asc"]]) {
      fireEvent.change(screen.getByLabelText(label), { target: { value } });
      await screen.findByRole("table");
    }
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await screen.findByText("Page 2 of 3");
    const previousUrl = listings().at(-1)?.[0];
    const dialog = await open();
    fill(dialog);
    submit(dialog);
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await screen.findByText("Page 2 of 3");
    expect(listings().at(-1)?.[0]).toBe(previousUrl);
    expect(screen.getByLabelText("Provider")).toHaveValue("AWS");
    expect(screen.getByLabelText("Sort by")).toHaveValue("name,asc");
  });

  it("locks the form while saving and ignores duplicate submit and Escape events", async () => {
    const pending = deferred<Response>();
    createReply = () => pending.promise;
    render(<ResourcesPage />);
    const dialog = await open();
    fill(dialog);
    const form = within(dialog).getByRole("form", { name: "Create cloud resource" });
    act(() => { fireEvent.submit(form); fireEvent.submit(form); });
    expect(within(dialog).getByRole("button", { name: "Saving…" })).toBeDisabled();
    expect(within(dialog).getByRole("button", { name: "Cancel" })).toBeDisabled();
    expect(within(dialog).getByLabelText("Name")).toBeDisabled();
    expect(within(dialog).getByText("Saving resource…")).toHaveAttribute("role", "status");
    expect(form).toHaveAttribute("aria-busy", "true");
    cancel(dialog);
    expect(dialog).toHaveAttribute("open");
    expect(posts()).toHaveLength(1);
    await act(async () => pending.resolve(json(created, 201)));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("shows backend 400 field errors and unrecognized errors without discarding input", async () => {
    createReply = async () => json({ status: 400, message: "Validation failed", errors: {
      externalResourceId: "Provider ID is invalid", name: "Name is invalid", request: "Additional validation failed",
    } }, 400);
    render(<ResourcesPage />);
    const dialog = await open();
    fill(dialog);
    submit(dialog);
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Validation failed");
    expect(within(dialog).getByRole("alert")).toHaveTextContent("request: Additional validation failed");
    expect(within(dialog).getByLabelText("External resource ID")).toHaveAccessibleDescription("Provider ID is invalid");
    expect(within(dialog).getByLabelText("Name")).toHaveAccessibleDescription("Name is invalid");
    expect(within(dialog).getByLabelText("External resource ID")).toHaveValue(input.externalResourceId);
    expect(within(dialog).getByLabelText("External resource ID")).toHaveFocus();
    expect(listings()).toHaveLength(1);
  });

  it.each([
    [404, "Cloud account not found: 7"],
    [409, "A resource with external ID AbC already exists in cloud account 7"],
    [500, "Internal server error"],
    [0, "Failed to fetch"],
  ])("preserves values and shows HTTP %s or network errors with a working retry", async (status, message) => {
    createReply = status === 0 ? async () => { throw new TypeError(message); }
      : async () => json({ status, message, errors: {} }, status);
    render(<ResourcesPage />);
    const dialog = await open();
    fill(dialog);
    submit(dialog);
    const alert = await within(dialog).findByRole("alert");
    expect(alert).toHaveTextContent(message);
    if (status === 409) expect(alert).toHaveTextContent("Resource already exists");
    await waitFor(() => expect(alert).toHaveFocus());
    for (const [label, value] of [["Cloud account", "7"], ["External resource ID", input.externalResourceId],
      ["Name", input.name], ["Category", input.category], ["Provider service", input.providerService], ["Region", input.region], ["Status", input.status]]) {
      expect(within(dialog).getByLabelText(label)).toHaveValue(value);
    }
    expect(listings()).toHaveLength(1);
    createReply = async () => json(created, 201);
    submit(dialog);
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(posts()).toHaveLength(2);
  });

  it("keeps creation success separate from a refresh error", async () => {
    render(<ResourcesPage />);
    const dialog = await open();
    fill(dialog);
    listReply = async () => json({ message: "Inventory refresh failed" }, 500);
    submit(dialog);
    expect(await screen.findByText("Resource “Web server” added. Current filters still apply.")).toBeInTheDocument();
    expect(await screen.findByRole("alert")).toHaveTextContent("Inventory refresh failed");
    expect(screen.getByRole("button", { name: "Retry resources" })).toBeEnabled();
    expect(posts()).toHaveLength(1);
  });
});
