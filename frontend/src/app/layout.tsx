import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "CloudCost Monitor",
  description: "Cloud resource and cost management platform",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
