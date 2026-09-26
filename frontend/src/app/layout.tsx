import type { Metadata } from "next";
import { ApplicationNavigation } from "@/components/application-navigation";
import "./globals.css";

export const metadata: Metadata = {
  title: "StratoSpend",
  description: "Cloud resource and cost management platform",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>
        <a className="skip-link" href="#main-content">Skip to content</a>
        <div className="app-shell">
          <ApplicationNavigation />
          <main className="content" id="main-content">{children}</main>
        </div>
      </body>
    </html>
  );
}
