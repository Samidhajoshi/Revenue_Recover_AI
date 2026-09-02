import { Route, Routes } from "react-router-dom";
import Layout from "./components/Layout";
import Overview from "./pages/Overview";
import RecoveryCases from "./pages/RecoveryCases";
import CaseDetails from "./pages/CaseDetails";
import BatchSimulation from "./pages/BatchSimulation";
import Customers from "./pages/Customers";

export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/" element={<Overview />} />
        <Route path="/cases" element={<RecoveryCases />} />
        <Route path="/cases/:id" element={<CaseDetails />} />
        <Route path="/customers" element={<Customers />} />
        <Route path="/simulation" element={<BatchSimulation />} />
      </Route>
    </Routes>
  );
}
