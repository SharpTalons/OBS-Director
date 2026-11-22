; OBS Director Windows installer (Inno Setup 6).
;
; Installs the plugin where OBS Studio 33 and later look for third-party plugins:
;   C:\ProgramData\obs-studio\plugins\obs-director\obs-director.dll
;   C:\ProgramData\obs-studio\plugins\obs-director\data\...
; and removes any copy left in the legacy locations, which OBS stops loading in 34.
;
; Built by .github/scripts/Package-Windows.ps1, which passes:
;   /DAppVersion=<version>  /DSourceDir=<folder holding obs-director.dll and data\>
;   /DOutputDir=<folder>    /DOutputName=<file name without .exe>

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
AppPublisherURL=https://github.com/SharpTalons/OBS-Director
AppSupportURL=https://github.com/SharpTalons/OBS-Director/issues
AppUpdatesURL=https://github.com/SharpTalons/OBS-Director/releases
VersionInfoVersion={#AppVersion}
VersionInfoDescription={#DisplayName} plugin for OBS Studio
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
UninstallDisplayName={#DisplayName}

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Messages]
WelcomeLabel2=This installs the [name/ver] plugin for OBS Studio {#MinObsMajor} or later.%n%nPlease close OBS before you continue.

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
