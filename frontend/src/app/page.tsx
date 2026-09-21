import { CloudAccountsDashboard } from "@/components/cloud-accounts-dashboard";

export default function Home() {
  return (
    <main>
      <aside className="sidebar">
        <div className="brand"><span>☁</span><div>CloudCost<small>MONITOR</small></div></div>
        <nav aria-label="Main navigation">
          <a className="active" href="#accounts">Cloud accounts</a>
          <span>Resources <em>Next</em></span>
          <span>Costs <em>Next</em></span>
          <span>Budgets <em>Later</em></span>
        </nav>
        <p className="milestone">Milestone 1<br/><strong>Foundation</strong></p>
      </aside>
      <section className="content" id="accounts">
        <header className="page-heading">
          <div><p className="eyebrow">CLOUD INVENTORY</p><h1>Cloud accounts</h1></div>
          <p>Connect the accounts whose resources and costs will be tracked.</p>
        </header>
        <CloudAccountsDashboard />
      </section>
    </main>
  );
}
