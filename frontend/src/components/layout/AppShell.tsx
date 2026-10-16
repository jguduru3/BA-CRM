import { Outlet } from "react-router-dom";
import { BottomNav } from "./BottomNav";
import { MobileNavDrawer } from "./MobileNav";
import { Sidebar } from "./Sidebar";
import { Topbar } from "./Topbar";

export function AppShell() {
  return (
    <div className="app-viewport w-full flex app-mesh overflow-hidden">
      <a href="#main-content" className="skip-link">
        Skip to content
      </a>
      <Sidebar />
      <div className="flex-1 flex flex-col min-w-0">
        <Topbar />
        {/* .app-main reserves room for the bottom bar (+ safe area) below lg */}
        <main id="main-content" tabIndex={-1} className="app-main flex-1 overflow-y-auto thin-scroll focus:outline-none">
          <div className="max-w-[1480px] mx-auto px-3.5 py-3.5 sm:px-4 sm:py-4 lg:px-6 lg:py-4 min-h-full flex flex-col">
            <Outlet />
          </div>
        </main>
      </div>
      <BottomNav />
      <MobileNavDrawer />
    </div>
  );
}
