"use client";

import { useCallback, useEffect, useState } from "react";
import { ApiError } from "@/lib/api-error";
import { cloudAccountApi } from "@/lib/cloud-accounts";
import { listCloudResources, type CloudResourceQuery } from "@/lib/cloud-resources";

type LoadState<T> = { status: "loading" }
  | { status: "success"; data: T }
  | { status: "error"; message: string; errors: Record<string, string> };

function useLoad<T>(load: () => Promise<T>, fallbackMessage: string) {
  const [revision, setRevision] = useState(0);
  const [result, setResult] = useState<{
    load: typeof load; revision: number; state: LoadState<T>;
  }>();

  useEffect(() => {
    let current = true;
    load().then(
      (data) => { if (current) setResult({ load, revision, state: { status: "success", data } }); },
      (reason: unknown) => {
        if (current) setResult({ load, revision, state: {
          status: "error", message: reason instanceof Error ? reason.message : fallbackMessage,
          errors: reason instanceof ApiError ? reason.errors : {},
        } });
      },
    );
    // Ignore late successes and failures after a query change, retry or unmount.
    return () => { current = false; };
  }, [load, revision, fallbackMessage]);

  // An earlier query's rows and totals must not appear under the new filters.
  const state: LoadState<T> = result?.load === load && result.revision === revision
    ? result.state : { status: "loading" };
  return { ...state, retry: () => setRevision((value) => value + 1) };
}

const defaults: CloudResourceQuery = { page: 0, size: 20, sort: "createdAt,desc" };

export function useCloudResourceInventory() {
  const [query, setQuery] = useState<CloudResourceQuery>(defaults);
  const loadResources = useCallback(() => listCloudResources(query), [query]);
  const resources = useLoad(loadResources, "Unable to load cloud resources");
  const accounts = useLoad(cloudAccountApi.list, "Unable to load cloud accounts");

  function changeFilters(filters: Partial<Omit<CloudResourceQuery, "page" | "size">>) {
    setQuery((current) => ({ ...current, ...filters, page: 0 }));
  }

  return {
    query, resources, accounts, changeFilters,
    resetFilters: () => setQuery({ ...defaults }),
    changePage: (page: number) => setQuery((current) => ({ ...current, page })),
    hasFilters: Boolean(query.cloudAccountId || query.provider || query.category || query.region || query.status),
  };
}
