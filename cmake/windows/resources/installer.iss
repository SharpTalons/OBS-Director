; OBS Director Windows installer (Inno Setup 6).
;
; Installs the plugin where OBS Studio 33 and later look for third-party plugins:
;   C:\ProgramData\obs-studio\plugins\obs-director\obs-director.dll
;   C:\ProgramData\obs-studio\plugins\obs-director\data\...
; removes any copy left in the legacy locations, which OBS stops loading in 34, and (a ticked
; option) opens TCP 4466/4468 and UDP 4467 in Windows Firewall for local networks only, removing
; any rule Windows made that blocks OBS (firewall.ps1).
;
; Built by .github/scripts/Package-Windows.ps1, which passes:
;   /DAppVersion=<version>  /DSourceDir=<folder holding obs-director.dll and data\>
;   /DOutputDir=<folder>    /DOutputName=<file name without .exe>
; and, when a code-signing certificate is set up, /DSign plus /Sdirector=<sign command $f>,
; which signs the installer and its uninstaller.

#ifndef AppVersion
  #error AppVersion is required
#endif
#ifndef SourceDir
  #error SourceDir is required
#endif

#define PluginName "obs-director"
#define DisplayName "OBS Director"
#define MinObsMajor 33

[Setup]
; Never change AppId: it is how Windows matches upgrades and the uninstaller.
AppId={{6C1F5E4B-2B7A-4C35-9D8E-3F0A1B7C9D21}
AppName={#DisplayName}
AppVersion={#AppVersion}
AppVerName={#DisplayName} {#AppVersion}
AppPublisher=SharpTalons
AppCopyright=Copyright (C) 2026 SharpTalons. Licensed under the GNU GPL v2 or later.
AppPublisherURL=https://github.com/SharpTalons/OBS-Director
AppSupportURL=https://github.com/SharpTalons/OBS-Director/issues
AppUpdatesURL=https://github.com/SharpTalons/OBS-Director/releases
VersionInfoVersion={#AppVersion}
VersionInfoTextVersion={#AppVersion}
VersionInfoProductName={#DisplayName}
VersionInfoProductVersion={#AppVersion}
VersionInfoProductTextVersion={#AppVersion}
VersionInfoCompany=SharpTalons
VersionInfoCopyright=Copyright (C) 2026 SharpTalons
VersionInfoDescription={#DisplayName} plugin for OBS Studio setup
; OBS decides the location, so the person isn't asked for one.
DefaultDirName={commonappdata}\obs-studio\plugins\{#PluginName}
DisableDirPage=yes
DisableProgramGroupPage=yes
DisableReadyPage=no
UsePreviousAppDir=no
; ProgramData\obs-studio\plugins is shared by every user on the PC.
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
; OBS holds the DLL open while it runs; ask to close it rather than leave a half-updated plugin.
CloseApplications=yes
RestartApplications=no
OutputDir={#OutputDir}
OutputBaseFilename={#OutputName}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
SetupIconFile=obs-director.ico
UninstallDisplayName={#DisplayName}
UninstallDisplayIcon={uninstallexe}
#ifdef Sign
; Signs this installer and the uninstaller it writes. The plugin DLL is signed before this runs.
SignTool=director
SignedUninstaller=yes
#endif

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Messages]
WelcomeLabel2=This installs the [name/ver] plugin for OBS Studio {#MinObsMajor} or later.%n%nIt lets the OBS Director app on your phone listen to OBS, start and stop the recording and mark clips.%n%nPlease close OBS before you continue.
FinishedLabel=[name] is installed.%n%nOpen OBS, then tap this PC in the OBS Director app on your phone. The first time, OBS asks you to allow the phone.

[InstallDelete]
; Legacy Program Files location (not loaded from OBS 34 onwards).
Type: files; Name: "{commonpf64}\obs-studio\obs-plugins\64bit\{#PluginName}.dll"
Type: files; Name: "{commonpf64}\obs-studio\obs-plugins\64bit\{#PluginName}.pdb"
Type: filesandordirs; Name: "{commonpf64}\obs-studio\data\obs-plugins\{#PluginName}"
; Legacy ProgramData layout with bin\64bit, and anything left from an earlier version.
Type: filesandordirs; Name: "{app}\bin"
Type: filesandordirs; Name: "{app}\data"

[Files]
Source: "{#SourceDir}\{#PluginName}.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#SourceDir}\data\*"; DestDir: "{app}\data"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "firewall.ps1"; DestDir: "{tmp}"; Flags: deleteafterinstall

[Tasks]
Name: "firewall"; Description: "Allow phones on this network through Windows Firewall (recommended)"; GroupDescription: "Windows Firewall:"

[Run]
; Opens TCP 4466/4468 and UDP 4467 for local networks only, on Private and Public networks, and
; removes any rule Windows made that blocks OBS (a block rule beats every allow rule).
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{tmp}\firewall.ps1"""; Flags: runhidden waituntilterminated; StatusMsg: "Allowing phones on this network through Windows Firewall..."; Tasks: firewall
; Open OBS at the end if the person wants, as themselves rather than as administrator.
Filename: "{code:ObsExe}"; WorkingDir: "{code:ObsBinDir}"; Description: "Start OBS Studio"; Flags: postinstall nowait skipifsilent runasoriginaluser; Check: ObsInstalled

[UninstallRun]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""OBS Director (control and video)"""; Flags: runhidden; RunOnceId: "FirewallControl"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""OBS Director (discovery)"""; Flags: runhidden; RunOnceId: "FirewallDiscovery"

[UninstallDelete]
Type: dirifempty; Name: "{app}"

[Code]
{ The installed OBS version from its uninstall entry, e.g. '33.0.2'; '' if OBS isn't found. }
function InstalledObsVersion(): String;
begin
  Result := '';
  if not RegQueryStringValue(HKLM64, 'SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\OBS Studio',
    'DisplayVersion', Result) then
    RegQueryStringValue(HKLM32, 'SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\OBS Studio',
      'DisplayVersion', Result);
end;

{ Where OBS is installed, e.g. 'C:\Program Files\obs-studio'; '' if not found. }
function ObsDir(): String;
begin
  Result := '';
  if not RegQueryStringValue(HKLM64, 'SOFTWARE\OBS Studio', '', Result) then
    RegQueryStringValue(HKLM32, 'SOFTWARE\OBS Studio', '', Result);
end;

function ObsBinDir(Param: String): String;
begin
  Result := AddBackslash(ObsDir()) + 'bin\64bit';
end;

function ObsExe(Param: String): String;
begin
  Result := ObsBinDir('') + '\obs64.exe';
end;

function ObsInstalled(): Boolean;
begin
  Result := (ObsDir() <> '') and FileExists(ObsExe(''));
end;

function MajorOf(Version: String): Integer;
var
  Dot: Integer;
begin
  Dot := Pos('.', Version);
  if Dot > 0 then
    Version := Copy(Version, 1, Dot - 1);
  Result := StrToIntDef(Version, 0);
end;

function InitializeSetup(): Boolean;
var
  Version: String;
begin
  Result := True;
  Version := InstalledObsVersion();
  if Version = '' then
  begin
    { Portable or unusual installs have no uninstall entry: let the person decide. }
    Result := SuppressibleMsgBox('OBS Studio was not found on this PC.' + #13#10#13#10 +
      '{#DisplayName} needs OBS Studio {#MinObsMajor} or later. Install anyway?',
      mbConfirmation, MB_YESNO, IDYES) = IDYES;
  end
  else if MajorOf(Version) < {#MinObsMajor} then
  begin
    SuppressibleMsgBox('{#DisplayName} needs OBS Studio {#MinObsMajor} or later, and this PC has OBS ' +
      Version + '.' + #13#10#13#10 + 'Update OBS from obsproject.com, then run this installer again.',
      mbError, MB_OK, IDOK);
    Result := False;
  end;
end;
