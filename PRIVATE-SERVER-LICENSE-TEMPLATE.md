# Private Server Licensing Template

This document is a practical template for separate written permission under the
root `LICENSE.txt`. It is not itself a license grant, legal advice, or a signed
agreement.

Use this when a server, organization, or private project wants to make a custom
private plugin, private fork, or private server feature that copies, adapts,
derives from, or borrows systems from Alec's Tamework source code beyond the
public source-available license.

Before using this for a real deal, have a lawyer review it for your jurisdiction,
payment terms, liability limits, tax handling, and signature process.

## Deal Shape

The default deal should be narrow:

- non-exclusive
- non-transferable
- non-sublicensable
- limited to one named server or organization
- limited to one named private plugin or project
- no public redistribution
- no standalone SDK/framework/toolkit redistribution
- no use in competing public mods or public frameworks
- no implied endorsement by Alec
- no ownership transfer of Alec's Tamework

Prefer this structure unless there is a clear reason to negotiate something
broader.

## Intake Checklist

Collect this before agreeing to terms:

- legal name or handle of the licensee
- server, organization, and project name
- Discord contact and business email if available
- whether the project is free, paid, monetized, or commercial
- expected player count, server count, and audience size
- which Tamework systems they want to copy, adapt, or derive from
- whether they need source access, private patches, or custom development
- whether the custom plugin will ever be distributed outside their own server
- whether any contractors or staff need access to the code
- requested term length
- requested support expectations
- proposed payment model

## Common Deal Models

### Private Adaptation License

Use this when the server's team builds its own private plugin using pieces of
Tamework source code or Tamework Systems.

Recommended terms:

- server-specific private use only
- no public release
- no redistribution to other servers
- no sublicense to partners or contractors except named staff/contractors
- license ends if payment stops or the agreement is breached
- custom plugin source stays private unless separately agreed

### Alec-Built Custom Plugin

Use this when Alec writes the custom private plugin or custom integration.

Recommended terms:

- separate payment for custom development
- clear delivery milestones
- Alec retains reusable framework code, abstractions, and general improvements
- licensee receives private server-use rights to the delivered plugin
- support and update obligations are written explicitly

### Prototype or Evaluation License

Use this when a server wants to test whether a custom adaptation is viable.

Recommended terms:

- short fixed term, such as 30 to 90 days
- internal testing only
- no production deployment unless upgraded to a full agreement
- no redistribution
- no promise of ongoing support

## Agreement Template

Replace bracketed text before sending. Remove optional clauses that do not apply.

```text
PRIVATE TAMEWORK SERVER LICENSE AGREEMENT

Effective Date: [DATE]

Licensor:
Alec (Alechilles)
Discord: alechilles
Email: [EMAIL IF USED]

Licensee:
[LEGAL NAME OR SERVER OWNER HANDLE]
[SERVER / ORGANIZATION NAME]
[CONTACT INFORMATION]

1. Background

Alec owns Alec's Tamework, a source-available Hytale mod framework and related
source code, assets, templates, examples, documentation, configuration schemas,
and Tamework Systems.

Licensee wants separate written permission to create, use, and operate a custom
private plugin or private server feature that copies, adapts, derives from, or
borrows from Alec's Tamework beyond the rights granted by the public Tamework
license.

The parties agree as follows.

2. Definitions

"Tamework" means Alec's Tamework and the Licensed Work described in the public
Tamework license.

"Tamework Systems" has the same meaning as in the public Tamework license.

"Private Project" means [PRIVATE PLUGIN / SERVER FEATURE NAME].

"Approved Server" means [SERVER NAME / SERVER NETWORK / ORGANIZATION].

"Approved Systems" means only the following Tamework systems, source files,
assets, configs, templates, or concepts:

- [SYSTEM / FILE / PACKAGE / FEATURE]
- [SYSTEM / FILE / PACKAGE / FEATURE]
- [SYSTEM / FILE / PACKAGE / FEATURE]

"Authorized Users" means Licensee's employees, staff, contractors, or operators
who need access to the Private Project for the Approved Server and who are bound
by confidentiality and use restrictions at least as protective as this agreement.

3. License Grant

Subject to this agreement, Alec grants Licensee a non-exclusive,
non-transferable, non-sublicensable, limited license to copy, adapt, modify, and
create derivative works from the Approved Systems solely as necessary to develop,
run, maintain, and operate the Private Project for the Approved Server.

This license does not grant any rights outside the Approved Server, Private
Project, Approved Systems, and term stated in this agreement.

4. Permitted Use

Licensee may:

- create and maintain the Private Project for the Approved Server;
- run the Private Project in development, staging, and production environments
  controlled by Licensee for the Approved Server;
- allow Authorized Users to access the Private Project as needed for server
  operations;
- make private backups and internal version-control copies; and
- modify the Private Project for the Approved Server during the term of this
  agreement.

5. Restrictions

Licensee may not:

- publish, sell, share, leak, sublicense, rent, lease, or redistribute Tamework,
  the Approved Systems, or the Private Project;
- use the Approved Systems in any public mod, public plugin, public framework,
  public toolkit, SDK, library, or product;
- use the Approved Systems for any server, organization, or project other than
  the Approved Server and Private Project;
- use the Approved Systems to build or support a competing public framework,
  companion system, tameable NPC framework, asset patching framework, or
  substantially similar reusable modding toolkit;
- remove copyright, attribution, license, provenance, or branding notices from
  copied Tamework materials;
- imply that Alec endorses, sponsors, operates, or officially supports the
  Approved Server unless Alec separately agrees in writing;
- disclose non-public source code, private patches, credentials, or technical
  materials provided by Alec; or
- assign this agreement, transfer this agreement, or allow another person or
  organization to exercise these rights.

6. Ownership

Alec retains all ownership and rights in Tamework, Tamework Systems, copied
Tamework code, copied Tamework assets, copied Tamework configs, copied Tamework
templates, and general-purpose improvements to Tamework.

Licensee owns its original server-specific content in the Private Project, but
only to the extent that content is original to Licensee and does not include,
derive from, or require Tamework or the Approved Systems.

Licensee receives no ownership interest in Tamework. This agreement is a limited
license only.

7. Improvements and Feedback

Unless the parties agree otherwise in writing, suggestions, bug reports, patches,
ideas, integration notes, and other feedback Licensee provides to Alec may be
used by Alec without restriction or payment.

Optional custom-development clause:

If Alec creates general-purpose fixes, abstractions, systems, APIs, docs, tests,
or improvements while working on the Private Project, Alec may reuse, publish,
license, sublicense, and commercialize those improvements as part of Tamework or
other Alec projects. Licensee's server-specific secrets, private business data,
private lore, private economy values, and private operational details are not
included in this reuse right.

8. Confidentiality

Each party must keep the other party's non-public source code, private patches,
credentials, security details, private business terms, and clearly confidential
materials confidential.

Confidentiality does not apply to information that is already public, becomes
public without breach, was already known by the receiving party, is independently
developed without use of confidential information, or must be disclosed by law.

9. Payment

Licensee will pay Alec as follows:

- setup fee: [AMOUNT / NONE]
- recurring fee: [AMOUNT / INTERVAL / NONE]
- revenue share: [PERCENTAGE / BASIS / NONE]
- custom development rate: [RATE / NONE]
- payment due dates: [TERMS]
- accepted payment method: [METHOD]

Failure to pay undisputed amounts within [NUMBER] days after written notice is a
material breach.

10. Support and Updates

Support included:

- [NONE / DISCORD SUPPORT / BUG FIXES / VERSION UPDATES / HOURS PER MONTH]

Support not included unless separately agreed:

- emergency response
- guaranteed uptime
- server administration
- compatibility with unrelated mods
- custom feature development
- Hytale platform changes outside Alec's control

11. Term

This agreement begins on the Effective Date and continues until [END DATE /
MONTH-TO-MONTH / TERMINATED UNDER SECTION 12].

Optional renewal:

The agreement renews for [RENEWAL PERIOD] unless either party gives at least
[NUMBER] days' written notice before the renewal date.

12. Termination

Alec may terminate this agreement if Licensee breaches the agreement and does not
fix the breach within [NUMBER] days after written notice.

Alec may terminate immediately if Licensee publishes, leaks, redistributes,
sublicenses, sells, or uses the Approved Systems outside the Approved Server or
Private Project.

Licensee may terminate by giving [NUMBER] days' written notice.

13. Effect of Termination

After termination, Licensee must stop using the Private Project and Approved
Systems, remove them from production and development environments, and destroy
copies under Licensee's control, except for archival copies required by law or
ordinary encrypted backups that are not restored except for legal compliance.

Sections covering ownership, confidentiality, payment owed, no warranty,
limitation of liability, and restrictions survive termination.

14. Attribution

Attribution required:

[YES / NO / ONLY IF PUBLIC CREDITS ARE SHOWN]

If attribution is required, Licensee will use:

"Uses private technology licensed from Alec's Tamework by Alechilles."

Attribution does not grant endorsement, sponsorship, or official status.

15. No Warranty

Tamework, the Approved Systems, and any Private Project materials are provided
"as is" unless this agreement expressly says otherwise.

16. Limitation of Liability

To the maximum extent permitted by law, Alec will not be liable for indirect,
incidental, special, consequential, exemplary, or punitive damages, lost profits,
lost data, lost goodwill, service interruption, or server downtime arising from
or related to this agreement.

Alec's total liability under this agreement will not exceed the amounts Licensee
paid Alec under this agreement during the [NUMBER] months before the claim.

17. No Exclusivity

This agreement is non-exclusive. Alec may continue developing, licensing,
selling, supporting, publishing, and commercializing Tamework, Tamework Systems,
similar features, similar systems, and similar private deals for other projects.

18. Governing Law

This agreement is governed by the laws of [STATE / COUNTRY], excluding conflict
of law rules.

19. Entire Agreement

This agreement is the entire agreement between the parties for the Private
Project and Approved Systems. It does not replace the public Tamework license
for uses outside this agreement.

20. Signatures

Licensor:

Name: Alec (Alechilles)
Signature: ______________________________
Date: __________________

Licensee:

Name: _________________________________
Title / Role: __________________________
Server / Organization: _________________
Signature: ______________________________
Date: __________________
```

## Terms To Decide Per Deal

Use these as defaults unless there is a reason to negotiate:

- term: 12 months or month-to-month
- cure period: 10 days for normal breaches
- immediate termination for leaks, public redistribution, or unauthorized server
  reuse
- liability cap: fees paid in the previous 3 to 12 months
- support: none unless paid separately
- attribution: optional for private servers, required if public credits mention
  the feature
- contractors: allowed only as Authorized Users under confidentiality
- renewal: automatic only if recurring payment is current

## Red Flags

Pause and review carefully if the server asks for:

- exclusivity
- sublicensing
- permanent rights after one payment
- rights to publish the private plugin later
- rights to use the plugin on multiple unrelated server networks
- rights to remove all attribution
- rights to resell the plugin or include it in a server product
- ownership of Tamework improvements
- broad "all current and future Tamework systems" access
- support promises without a paid support term

## Plain-English Summary For Prospects

Normal Tamework use does not need a deal. You can use unmodified Tamework as a
dependency and configure it through documented assets, APIs, and examples under
the public license.

You need a separate deal only if you want to copy, modify, fork, privately embed,
or derive custom server code from Tamework systems. Those deals are project-
specific and do not let you publish, resell, sublicense, or reuse the systems
outside the approved private server project.
