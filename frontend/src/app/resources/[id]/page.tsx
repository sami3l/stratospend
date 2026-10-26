import { CloudResourceDetails } from "@/components/cloud-resource-details";

export default async function ResourceDetailsPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <CloudResourceDetails id={id} />;
}
