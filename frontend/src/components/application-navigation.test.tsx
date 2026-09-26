import { render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { usePathname } from "next/navigation";
import { ApplicationNavigation } from "./application-navigation";
import Home from "@/app/page";
import ResourcesPage from "@/app/resources/page";

vi.mock("next/navigation", () => ({ usePathname: vi.fn() }));

describe("Shared application navigation", () => {
  beforeEach(() => {
    vi.mocked(usePathname).mockReturnValue("/");
    vi.stubGlobal("fetch", vi.fn().mockImplementation(async (input: string) => new Response(JSON.stringify(
      input.includes("cloud-resources") ? { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } : [],
    ), { status: 200 })));
  });
  afterEach(() => vi.unstubAllGlobals());

  it("links to resources and preserves the cloud accounts page", async () => {
    render(<><ApplicationNavigation /><Home /></>);
    const navigation = within(screen.getByRole("navigation", { name: "Main navigation" }));
    expect(navigation.getByRole("link", { name: "Resources" })).toHaveAttribute("href", "/resources");
    expect(navigation.getByRole("link", { name: "Resources" })).not.toHaveAttribute("aria-current");
    expect(navigation.getByRole("link", { name: "Cloud accounts" })).toHaveAttribute("href", "/");
    expect(navigation.getByRole("link", { name: "Cloud accounts" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("heading", { name: "Cloud accounts", level: 1 })).toBeInTheDocument();
    expect(await screen.findByText("No cloud accounts yet")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /add cloud account/i })).toBeInTheDocument();
  });

  it("highlights resources and keeps future destinations disabled", async () => {
    vi.mocked(usePathname).mockReturnValue("/resources");
    render(<><ApplicationNavigation /><ResourcesPage /></>);
    const navigation = within(screen.getByRole("navigation", { name: "Main navigation" }));
    expect(navigation.getByRole("link", { name: "Resources" })).toHaveAttribute("aria-current", "page");
    expect(navigation.getByRole("link", { name: "Cloud accounts" })).not.toHaveAttribute("aria-current");
    expect(navigation.getByText(/Costs/)).toHaveAttribute("aria-disabled", "true");
    expect(navigation.getByText(/Budgets/)).toHaveAttribute("aria-disabled", "true");
    expect(navigation.getAllByRole("link")).toHaveLength(2);
    expect(screen.getByText("StratoSpend")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Cloud resources", level: 1 })).toBeInTheDocument();
    expect(await screen.findByText("No cloud resources yet")).toBeInTheDocument();
  });
});
