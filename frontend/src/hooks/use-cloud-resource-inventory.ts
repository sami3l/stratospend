"use client";

import { useCallback, useState } from "react";
import { cloudAccountApi } from "@/lib/cloud-accounts";
import { listCloudResources, type CloudResourceQuery } from "@/lib/cloud-resources";
import { useLoad } from "./use-load";

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
