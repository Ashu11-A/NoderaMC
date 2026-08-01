// About, in Material components rather than in prose.
//
// The desktop About screen is a document: sections, paragraphs, a licence table. On a phone that is
// something nobody reads. This is the same facts as list items — a value per row, and the licences
// behind a row you can open.
import { useEffect, useState } from "react";
import { FiCode, FiExternalLink, FiFolder, FiLink, FiPackage, FiTag } from "react-icons/fi";
import { Card, ListItem } from "../m3/components";
import { aboutBuild, type AboutBuild } from "../play";
import { linkNote, useExternalLink } from "../links";

export function AboutScreen() {
  const [about, setAbout] = useState<AboutBuild | null>(null);
  const [licences, setLicences] = useState(false);
  const repository = useExternalLink();

  useEffect(() => {
    aboutBuild().then(setAbout).catch(() => {});
  }, []);

  if (!about) {
    return <p className="p-2 text-[14px] text-[var(--md-sys-color-on-surface-variant)]">…</p>;
  }

  if (licences) {
    return (
      <div className="flex flex-col gap-2">
        <Card onClick={() => setLicences(false)}>
          <ListItem headline="Back" supporting={`${about.packages.length} packages`} />
        </Card>
        <Card>
          {about.packages.map((pkg) => (
            <ListItem
              key={`${pkg.ecosystem}:${pkg.name}@${pkg.version}`}
              headline={pkg.name}
              supporting={`${pkg.version} · ${pkg.licence || "licence not declared"}`}
            />
          ))}
        </Card>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-2">
      <Card>
        <ListItem leading={<FiTag />} headline={about.version} supporting="Version" />
        <ListItem leading={<FiCode />} headline={about.licence} supporting="Licence" />
        <ListItem
          leading={<FiLink />}
          headline={`Control protocol v${about.protocol_version}`}
          supporting="Spoken to the peer worker"
        />
        <ListItem leading={<FiFolder />} headline={about.config_dir} supporting="App files" />
        {/* The phone screen had no repository row at all — the desktop one had a link that opened
            nothing, and this had nothing. Tapping it opens a Custom Tab: the user's own browser,
            drawn inside this task, so the back gesture comes back here. */}
        {about.repository && (
          <ListItem
            leading={<FiExternalLink />}
            headline={about.repository}
            supporting={linkNote(repository.state) || "Source code"}
            onClick={() => repository.open(about.repository)}
          />
        )}
      </Card>
      <Card onClick={() => setLicences(true)}>
        <ListItem
          leading={<FiPackage />}
          headline="Open-source licences"
          supporting={`${about.packages.length} packages`}
        />
      </Card>
      <Card>
        <ListItem headline={about.name} supporting={about.description} />
      </Card>
    </div>
  );
}
