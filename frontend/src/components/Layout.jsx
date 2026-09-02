import { NavLink, Outlet } from "react-router-dom";

const links = [
  { to: "/", label: "Overview", end: true, icon: "▦" },
  { to: "/cases", label: "Recovery Cases", icon: "≡" },
  { to: "/customers", label: "Customers", icon: "◍" },
  { to: "/simulation", label: "Batch Simulation", icon: "▷" },
];

export default function Layout() {
  return (
    <div className="app-shell">
      <aside className="app-sidebar">
        <div className="brand">
          <span className="brand-mark">R</span>
          RecoverAI
        </div>

        <nav className="app-nav">
          <div className="nav-section-label">Menu</div>
          {links.map((l) => (
            <NavLink
              key={l.to}
              to={l.to}
              end={l.end}
              className={({ isActive }) => "nav-link" + (isActive ? " active" : "")}
            >
              <span className="nav-icon">{l.icon}</span>
              {l.label}
            </NavLink>
          ))}
        </nav>

        <div className="sidebar-footer">
          Autonomous revenue recovery controller. AI decides what's likely to work; policy decides what's allowed.
        </div>
      </aside>

      <main className="app-main">
        <Outlet />
      </main>
    </div>
  );
}
