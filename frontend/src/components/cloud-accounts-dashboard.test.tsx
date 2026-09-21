import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { CloudAccountsDashboard } from "./cloud-accounts-dashboard";

const account = {
  id: 1, name: "Production AWS", provider: "AWS", externalAccountId: "123456789012",
  environment: "PRODUCTION", region: "eu-west-3", active: true,
  createdAt: "2026-09-17T10:00:00Z", updatedAt: "2026-09-17T10:00:00Z",
};

describe("CloudAccountsDashboard", () => {
  beforeEach(() => { vi.restoreAllMocks(); });

  it("loads and displays registered accounts", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, json: async () => [account] }));
    render(<CloudAccountsDashboard />);
    expect(await screen.findByText("Production AWS")).toBeInTheDocument();
    expect(screen.getByText("123456789012")).toBeInTheDocument();
    expect(screen.getByText(/Active$/)).toBeInTheDocument();
  });

  it("registers a new account", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => [] })
      .mockResolvedValueOnce({ ok: true, json: async () => account });
    vi.stubGlobal("fetch", fetchMock);
    render(<CloudAccountsDashboard />);
    await screen.findByText("No cloud accounts yet");
    fireEvent.click(screen.getByText(/add cloud account/i));
    fireEvent.change(screen.getByLabelText("Account name"), { target: { value: "Production AWS" } });
    fireEvent.change(screen.getByLabelText("External account ID"), { target: { value: "123456789012" } });
    fireEvent.click(screen.getByText("Register account"));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(await screen.findByText("Production AWS")).toBeInTheDocument();
  });
});
