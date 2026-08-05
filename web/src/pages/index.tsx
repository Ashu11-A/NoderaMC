import { SiteFooter, SiteHeader } from "../components/chrome";
import { DoorStrip, FeatureGrid, Hero, HonestyBand, HowItWorks, NoModBand } from "../components/landing";

export default function Page() {
  return (
    <div className="min-h-screen bg-bg">
      <SiteHeader landing />
      <main>
        <Hero />
        <FeatureGrid />
        <HowItWorks />
        <NoModBand />
        <HonestyBand />
        <DoorStrip />
      </main>
      <SiteFooter />
    </div>
  );
}
