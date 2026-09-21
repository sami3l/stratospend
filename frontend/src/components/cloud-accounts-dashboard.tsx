"use client";

import { FormEvent, useCallback, useEffect, useState } from "react";
import { CloudAccount, cloudAccountApi, CreateCloudAccount } from "@/lib/cloud-accounts";

const emptyForm: CreateCloudAccount = {
  name: "", provider: "AWS", externalAccountId: "", environment: "DEVELOPMENT", region: "eu-west-3",
};

export function CloudAccountsDashboard() {
  const [accounts, setAccounts] = useState<CloudAccount[]>([]);
  const [form, setForm] = useState<CreateCloudAccount>(emptyForm);
  const [showForm, setShowForm] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const loadAccounts = useCallback(async () => {
    setLoading(true); setError("");
    try { setAccounts(await cloudAccountApi.list()); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "Unable to load cloud accounts"); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => {
    cloudAccountApi.list()
      .then(setAccounts)
      .catch((reason) => setError(reason instanceof Error ? reason.message : "Unable to load cloud accounts"))
      .finally(() => setLoading(false));
  }, []);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setSaving(true); setError("");
    try {
      const created = await cloudAccountApi.create(form);
      setAccounts((current) => [created, ...current]);
      setForm(emptyForm); setShowForm(false);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Unable to create cloud account");
    } finally { setSaving(false); }
  }

  async function toggle(account: CloudAccount) {
    setError("");
    try {
      const updated = await cloudAccountApi.changeStatus(account.id, !account.active);
      setAccounts((current) => current.map((item) => item.id === updated.id ? updated : item));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Unable to update cloud account");
    }
  }

  return <>
    <section className="summary" aria-label="Account summary">
      <article><span>Total accounts</span><strong>{accounts.length}</strong></article>
      <article><span>Active accounts</span><strong>{accounts.filter((a) => a.active).length}</strong></article>
      <article><span>Providers</span><strong>{new Set(accounts.map((a) => a.provider)).size}</strong></article>
      <button onClick={() => setShowForm((visible) => !visible)}>+ Add cloud account</button>
    </section>

    {showForm && <form className="account-form" onSubmit={submit}>
      <div className="form-heading"><div><h2>Register cloud account</h2><p>No cloud credentials are stored.</p></div><button type="button" className="close" onClick={() => setShowForm(false)}>×</button></div>
      <label>Account name<input required maxLength={80} placeholder="Production AWS" value={form.name} onChange={(e) => setForm({...form, name:e.target.value})}/></label>
      <label>Provider<select value={form.provider} onChange={(e) => setForm({...form, provider:e.target.value as CreateCloudAccount["provider"]})}><option>AWS</option><option>AZURE</option><option>GCP</option></select></label>
      <label>External account ID<input required placeholder="123456789012" value={form.externalAccountId} onChange={(e) => setForm({...form, externalAccountId:e.target.value})}/></label>
      <label>Environment<select value={form.environment} onChange={(e) => setForm({...form, environment:e.target.value as CreateCloudAccount["environment"]})}><option value="DEVELOPMENT">Development</option><option value="STAGING">Staging</option><option value="PRODUCTION">Production</option></select></label>
      <label>Primary region<input required pattern="[a-z0-9-]+" placeholder="eu-west-3" value={form.region} onChange={(e) => setForm({...form, region:e.target.value})}/></label>
      <button className="submit" disabled={saving}>{saving ? "Saving…" : "Register account"}</button>
    </form>}

    {error && <div className="error" role="alert">{error}<button onClick={() => void loadAccounts()}>Retry</button></div>}
    {loading ? <div className="state">Loading cloud accounts…</div> : accounts.length === 0 ?
      <div className="state"><strong>No cloud accounts yet</strong><p>Add your first account to start building the inventory.</p></div> :
      <section className="account-list">
        <div className="table-header"><span>Account</span><span>Provider</span><span>Environment</span><span>Region</span><span>Status</span><span /></div>
        {accounts.map((account) => <article className="account-row" key={account.id}>
          <div><strong>{account.name}</strong><small>{account.externalAccountId}</small></div>
          <span className={`provider ${account.provider.toLowerCase()}`}>{account.provider}</span>
          <span>{label(account.environment)}</span><code>{account.region}</code>
          <span className={account.active ? "enabled" : "disabled"}>● {account.active ? "Active" : "Inactive"}</span>
          <button className="toggle" onClick={() => void toggle(account)}>{account.active ? "Deactivate" : "Activate"}</button>
        </article>)}
      </section>}
  </>;
}

function label(value: string) { return value.charAt(0) + value.slice(1).toLowerCase(); }
