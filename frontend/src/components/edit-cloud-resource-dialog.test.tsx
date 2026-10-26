import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
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
const problem = (status: number, message: string, errors = {}) =>
  json({ timestamp: "2026-09-25T08:00:00Z", status, message, errors }, status);
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

describe("Cloud resource editing", () => {
  const fetchMock = vi.fn<typeof fetch>();
  let updateReply: (body: Record<string, unknown>) => Promise<Response>;

  beforeEach(() => {
    fetchMock.mockReset();
    updateReply = async (body) => json({ ...resource, ...body, updatedAt: "2026-09-25T08:00:00Z" });
    fetchMock.mockImplementation((_url, init) => init?.method === "PUT"
      ? updateReply(JSON.parse(String(init.body))) : Promise.resolve(json(resource)));
    vi.stubGlobal("fetch", fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  function puts() { return fetchMock.mock.calls.filter(([, init]) => init?.method === "PUT"); }
  function gets() { return fetchMock.mock.calls.filter(([, init]) => !init?.method); }

  async function open() {
    render(<CloudResourceDetails id="42" />);
    const edit = await screen.findByRole("button", { name: "Edit resource" });
    edit.focus();
    fireEvent.click(edit);
    return screen.getByRole("dialog", { name: "Edit cloud resource" });
  }
  function change(dialog: HTMLElement, label: string, value: string) {
    fireEvent.change(within(dialog).getByLabelText(label), { target: { value } });
  }
  function save(dialog: HTMLElement) { fireEvent.click(within(dialog).getByRole("button", { name: "Save changes" })); }

  it("opens prefilled with editable fields and shows immutable fields as text", async () => {
    const dialog = await open();
    expect(within(dialog).getByLabelText("Name")).toHaveValue("Web server");
    expect(within(dialog).getByLabelText("Name")).toHaveFocus();
    expect(within(dialog).getByRole("combobox", { name: "Category" })).toHaveValue("COMPUTE");
    expect(within(dialog).getByLabelText("Provider service")).toHaveValue("EC2");
    expect(within(dialog).getByLabelText("Region")).toHaveValue("eu-west-1");
    expect(within(dialog).getByRole("combobox", { name: "Status" })).toHaveValue("ACTIVE");
    expect(within(dialog).getByText("Production AWS")).toBeInTheDocument();
    expect(within(dialog).getByText(resource.externalResourceId).tagName).toBe("CODE");
    expect(within(dialog).getByText(/cannot be changed after registration/)).toBeInTheDocument();
    expect(within(dialog).getAllByRole("textbox")).toHaveLength(3);
    expect(within(dialog).queryByRole("combobox", { name: "Cloud account" })).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText("External resource ID")).not.toBeInTheDocument();
  });

  it("closes on Cancel or Escape without saving and returns focus to Edit", async () => {
    let dialog = await open();
    change(dialog, "Name", "Discarded");
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit resource" })).toHaveFocus();

    fireEvent.click(screen.getByRole("button", { name: "Edit resource" }));
    dialog = screen.getByRole("dialog", { name: "Edit cloud resource" });
    expect(within(dialog).getByLabelText("Name")).toHaveValue("Web server");
    fireEvent(dialog, new Event("cancel", { cancelable: true }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(puts()).toHaveLength(0);
    expect(screen.getByRole("heading", { level: 1, name: "Web server" })).toBeInTheDocument();
  });

  it("validates locally, focuses the first invalid field and sends nothing", async () => {
    const dialog = await open();
    change(dialog, "Name", "   ");
    change(dialog, "Region", "EU West");
    save(dialog);
    expect(within(dialog).getByText("Check the highlighted fields.")).toBeInTheDocument();
    expect(within(dialog).getByText("Name is required.")).toBeInTheDocument();
    expect(within(dialog).getByText("Use only lowercase letters, digits and hyphens, or global.")).toBeInTheDocument();
    expect(within(dialog).getByLabelText("Name")).toHaveAttribute("aria-invalid", "true");
    expect(within(dialog).getByLabelText("Name")).toHaveFocus();
    expect(puts()).toHaveLength(0);

    change(dialog, "Name", "Web server");
    expect(within(dialog).queryByText("Name is required.")).not.toBeInTheDocument();
    expect(within(dialog).getByLabelText("Name")).toHaveAttribute("aria-invalid", "false");
  });

  it("sends only editable fields, then shows the server response without refetching", async () => {
    updateReply = async (body) => json({ ...resource, ...body, name: "Web server v2", updatedAt: "2026-09-25T08:00:00Z" });
    const dialog = await open();
    change(dialog, "Name", "  Web server v2  ");
    fireEvent.change(within(dialog).getByRole("combobox", { name: "Status" }), { target: { value: "INACTIVE" } });
    change(dialog, "Region", "global");
    save(dialog);

    expect(await screen.findByRole("heading", { level: 1, name: "Web server v2" })).toBeInTheDocument();
    const [[url, init]] = puts();
    expect(String(url)).toBe("http://localhost:8080/api/v1/cloud-resources/42");
    expect(init?.headers).toEqual({ "Content-Type": "application/json" });
    expect(JSON.parse(String(init?.body))).toEqual({
      name: "  Web server v2  ", category: "COMPUTE", providerService: "EC2", region: "global", status: "INACTIVE",
    });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Resource “Web server v2” updated.");
    expect(screen.getByRole("button", { name: "Edit resource" })).toHaveFocus();
    expect(screen.getByText("Inactive")).toBeInTheDocument();
    expect(screen.getByText("global")).toBeInTheDocument();
    expect(screen.getByText("2026-09-25 08:00:00 UTC")).toBeInTheDocument();
    expect(gets()).toHaveLength(1);

    fireEvent.click(screen.getByRole("button", { name: "Edit resource" }));
    const reopened = screen.getByRole("dialog", { name: "Edit cloud resource" });
    expect(within(reopened).getByLabelText("Name")).toHaveValue("Web server v2");
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("closes without a request when nothing changed", async () => {
    const dialog = await open();
    change(dialog, "Name", "Temporary");
    change(dialog, "Name", "Web server");
    save(dialog);
    expect(await screen.findByText("No changes to save.")).toHaveAttribute("role", "status");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit resource" })).toHaveFocus();
    expect(puts()).toHaveLength(0);
  });

  it("locks the form while saving and ignores duplicate submits and Escape", async () => {
    const pending = deferred<Response>();
    updateReply = () => pending.promise;
    const dialog = await open();
    change(dialog, "Name", "Web server v2");
    const form = within(dialog).getByRole("form", { name: "Edit cloud resource" });
    act(() => { fireEvent.submit(form); fireEvent.submit(form); });
    expect(within(dialog).getByRole("button", { name: "Saving…" })).toBeDisabled();
    expect(within(dialog).getByRole("button", { name: "Cancel" })).toBeDisabled();
    expect(within(dialog).getByLabelText("Name")).toBeDisabled();
    expect(within(dialog).getByText("Saving resource…")).toHaveAttribute("role", "status");
    fireEvent(dialog, new Event("cancel", { cancelable: true }));
    expect(screen.getByRole("dialog")).toBeInTheDocument();

    await act(async () => pending.resolve(json({ ...resource, name: "Web server v2" })));
    expect(await screen.findByRole("heading", { level: 1, name: "Web server v2" })).toBeInTheDocument();
    expect(puts()).toHaveLength(1);
  });

  it("shows backend field errors and unrecognized errors without discarding input", async () => {
    updateReply = async () => problem(400, "Validation failed", { region: "must not be blank", tags: "is not supported" });
    const dialog = await open();
    change(dialog, "Name", "Web server v2");
    save(dialog);
    const alert = await within(dialog).findByRole("alert");
    expect(alert).toHaveTextContent("Validation failed");
    expect(alert).toHaveTextContent("tags: is not supported");
    expect(alert).not.toHaveTextContent("region:");
    expect(within(dialog).getByText("must not be blank")).toBeInTheDocument();
    expect(within(dialog).getByLabelText("Region")).toHaveAttribute("aria-invalid", "true");
    expect(within(dialog).getByLabelText("Region")).toHaveFocus();
    expect(within(dialog).getByLabelText("Name")).toHaveValue("Web server v2");
    expect(screen.getByRole("heading", { level: 1, name: "Web server" })).toBeInTheDocument();
  });

  it.each([
    ["HTTP 404", () => problem(404, "Cloud resource 42 was not found")],
    ["HTTP 500", () => problem(500, "Database unavailable")],
    ["a network failure", () => Promise.reject(new TypeError("Failed to fetch"))],
  ])("keeps input after %s and saves on retry", async (_case, failure) => {
    updateReply = failure as () => Promise<Response>;
    const dialog = await open();
    change(dialog, "Provider service", "EC2 Spot");
    save(dialog);
    const alert = await within(dialog).findByRole("alert");
    expect(alert).toHaveFocus();
    expect(within(dialog).getByLabelText("Provider service")).toHaveValue("EC2 Spot");
    expect(within(dialog).getByRole("button", { name: "Save changes" })).toBeEnabled();

    updateReply = async (body) => json({ ...resource, ...body });
    save(dialog);
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByRole("status")).toHaveTextContent("Resource “Web server” updated.");
    expect(screen.getByText("EC2 Spot")).toBeInTheDocument();
    expect(puts()).toHaveLength(2);
  });
});
