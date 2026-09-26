import { CloudAccountsDashboard } from "@/components/cloud-accounts-dashboard";

export default function Home() {
  return (
    <>
      <header className="page-heading">
        <div><p className="eyebrow">CLOUD INVENTORY</p><h1>Cloud accounts</h1></div>
        <p>Connect the accounts whose resources and costs will be tracked.</p>
      </header>
      <CloudAccountsDashboard />
    </>
  );
}
