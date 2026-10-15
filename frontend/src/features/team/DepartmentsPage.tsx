import { Network } from "lucide-react";
import type { Department } from "@/lib/types";
import { Column } from "@/components/ui/DataTable";
import { SimpleCrudPage, SimpleField } from "@/features/generic/SimpleCrudPage";

const columns: Column<Department>[] = [
  { key: "name", header: "Department", sortValue: (r) => r.name, render: (r) => <span className="text-sm font-medium">{r.name}</span> },
  { key: "description", header: "Description", render: (r) => <span className="text-sm truncate">{(r.description as string) || "—"}</span> },
];

const fields: SimpleField[] = [
  { key: "name", label: "Name", type: "text", required: true },
  { key: "description", label: "Description", type: "textarea" },
];

export default function DepartmentsPage() {
  return (
    <SimpleCrudPage<Department>
      resource="departments"
      label="Departments"
      icon={Network}
      columns={columns}
      fields={fields}
      defaults={{}}
      titleOf={(r) => r.name}
      searchable={false}
    />
  );
}
