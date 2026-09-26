"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

export function ApplicationNavigation() {
  const pathname = usePathname();
  return <aside className="sidebar">
    <div className="brand"><span aria-hidden="true">☁</span><div>StratoSpend</div></div>
    <nav aria-label="Main navigation">
      {[{ href: "/", label: "Cloud accounts" }, { href: "/resources", label: "Resources" }].map(({ href, label }) => {
        const active = pathname === href;
        return <Link key={href} href={href} className={active ? "active" : undefined}
          aria-current={active ? "page" : undefined}>{label}</Link>;
      })}
      <span aria-disabled="true">Costs <em>Next</em></span>
      <span aria-disabled="true">Budgets <em>Later</em></span>
    </nav>
    <p className="milestone">Cloud inventory<br/><strong>Accounts and resources</strong></p>
  </aside>;
}
