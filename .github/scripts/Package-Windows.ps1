[CmdletBinding()]
param(
    [ValidateSet('x64')]
    [string] $Target = 'x64',
    [ValidateSet('Debug', 'RelWithDebInfo', 'Release', 'MinSizeRel')]
    [string] $Configuration = 'RelWithDebInfo'
)

$ErrorActionPreference = 'Stop'

if ( $DebugPreference -eq 'Continue' ) {
    $VerbosePreference = 'Continue'
    $InformationPreference = 'Continue'
}

if ( $env:CI -eq $null ) {
    throw "Package-Windows.ps1 requires CI environment"
}

if ( ! ( [System.Environment]::Is64BitOperatingSystem ) ) {
    throw "Packaging script requires a 64-bit system to build and run."
}

if ( $PSVersionTable.PSVersion -lt '7.2.0' ) {
    Write-Warning 'The packaging script requires PowerShell Core 7. Install or upgrade your PowerShell version: https://aka.ms/pscore6'
    exit 2
}

function Package {
    trap {
        Write-Error $_
        exit 2
    }

    $ScriptHome = $PSScriptRoot
    $ProjectRoot = Resolve-Path -Path "$PSScriptRoot/../.."
    $BuildSpecFile = "${ProjectRoot}/buildspec.json"

    $UtilityFunctions = Get-ChildItem -Path $PSScriptRoot/utils.pwsh/*.ps1 -Recurse

    foreach( $Utility in $UtilityFunctions ) {
        Write-Debug "Loading $($Utility.FullName)"
        . $Utility.FullName
    }

    $BuildSpec = Get-Content -Path ${BuildSpecFile} -Raw | ConvertFrom-Json
    $ProductName = $BuildSpec.name
    $ProductVersion = $BuildSpec.version

    # The installer, named like the app: OBS-Director-<version>-Windows-Setup.exe
    $OutputName = "OBS-Director-${ProductVersion}-Windows-Setup"
    $SourceDir = "${ProjectRoot}/release/${Configuration}/${ProductName}"

    if ( ! ( Test-Path -Path "${SourceDir}/${ProductName}.dll" ) ) {
        throw "${ProductName}.dll is missing from ${SourceDir}. Did the build's install step run?"
    }

    Remove-Item -ErrorAction SilentlyContinue -Path "${ProjectRoot}/release/OBS-Director-*-Windows-Setup.exe"

    Log-Group "Finding Inno Setup..."
    $Iscc = "${env:ProgramFiles(x86)}/Inno Setup 6/ISCC.exe"
    if ( ! ( Test-Path -Path $Iscc ) ) {
        Invoke-External choco install innosetup --yes --no-progress
    }
    if ( ! ( Test-Path -Path $Iscc ) ) {
        throw "Inno Setup 6 (ISCC.exe) is not available."
    }
    Log-Group

    $IsccArgs = @(
        "/DAppVersion=${ProductVersion}"
        "/DSourceDir=$((Resolve-Path -Path $SourceDir).Path)"
        "/DOutputDir=$((Resolve-Path -Path "${ProjectRoot}/release").Path)"
        "/DOutputName=${OutputName}"
    )

    # Code signing with Azure Artifact Signing, when its six secrets are set (docs/RELEASING.md).
    # Signs the plugin DLL here, then Inno Setup signs the installer and its uninstaller.
    $SignVars = @('AZURE_TENANT_ID', 'AZURE_CLIENT_ID', 'AZURE_CLIENT_SECRET',
        'ARTIFACT_SIGNING_ENDPOINT', 'ARTIFACT_SIGNING_ACCOUNT', 'ARTIFACT_SIGNING_PROFILE')
    $Missing = @($SignVars | Where-Object { -not [System.Environment]::GetEnvironmentVariable($_) })
    $Signing = $Missing.Count -eq 0
    if ( $Signing ) {
        Log-Group "Setting up code signing..."
        $Tools = "${env:RUNNER_TEMP}/signing-tools"
        Invoke-External nuget install Microsoft.ArtifactSigning.Client -x -OutputDirectory $Tools -NonInteractive
        Invoke-External nuget install Microsoft.Windows.SDK.BuildTools -x -OutputDirectory $Tools -NonInteractive
        $Dlib = Get-ChildItem -Path $Tools -Recurse -Filter 'Azure.CodeSigning.Dlib.dll' |
            Where-Object { $_.FullName -match '[\\/]x64[\\/]' } | Select-Object -First 1
        $SignTool = Get-ChildItem -Path $Tools -Recurse -Filter 'signtool.exe' |
            Where-Object { $_.FullName -match '[\\/]x64[\\/]' } | Sort-Object FullName -Descending | Select-Object -First 1
        if ( ! $Dlib -or ! $SignTool ) {
            throw "Code signing is set up but its tools weren't found (dlib: $($Dlib.FullName), signtool: $($SignTool.FullName))."
        }
        # Only the service principal in the AZURE_* variables may sign: no other way of logging in.
        $Metadata = "${env:RUNNER_TEMP}/signing-metadata.json"
        @{
            Endpoint = $env:ARTIFACT_SIGNING_ENDPOINT
            CodeSigningAccountName = $env:ARTIFACT_SIGNING_ACCOUNT
            CertificateProfileName = $env:ARTIFACT_SIGNING_PROFILE
            ExcludeCredentials = @('ManagedIdentityCredential', 'WorkloadIdentityCredential',
                'SharedTokenCacheCredential', 'VisualStudioCredential', 'VisualStudioCodeCredential',
                'AzureCliCredential', 'AzurePowerShellCredential', 'AzureDeveloperCliCredential',
                'InteractiveBrowserCredential')
        } | ConvertTo-Json | Set-Content -Path $Metadata -Encoding utf8
        $SignArgs = @('sign', '/fd', 'SHA256', '/tr', 'http://timestamp.acs.microsoft.com', '/td', 'SHA256',
            '/d', 'OBS Director', '/du', 'https://github.com/SharpTalons/OBS-Director',
            '/dlib', $Dlib.FullName, '/dmdf', $Metadata)
        Log-Group

        Log-Group "Signing ${ProductName}.dll..."
        Invoke-External $SignTool.FullName @SignArgs "${SourceDir}/${ProductName}.dll"
        Log-Group

        # Inno Setup runs this for the installer and the uninstaller; $f is the quoted file.
        $Quoted = ($SignArgs | ForEach-Object { if ( $_ -match '\s' ) { "`"$_`"" } else { $_ } }) -join ' '
        $IsccArgs += "/DSign=1"
        $IsccArgs += "/Sdirector=`"$($SignTool.FullName)`" ${Quoted} `$f"
    } elseif ( $env:GITHUB_EVENT_NAME -eq 'push' ) {
        Write-Output "::warning::The Windows installer is NOT code-signed: set the $($Missing -join ', ') secret(s) (docs/RELEASING.md). Windows SmartScreen will warn people who run it."
    }

    Log-Group "Building ${OutputName}.exe..."
    Invoke-External $Iscc @IsccArgs "${ProjectRoot}/cmake/windows/resources/installer.iss"
    Log-Group

    if ( $Signing ) {
        Log-Group "Checking the signatures..."
        Invoke-External $SignTool.FullName verify /pa /v "${SourceDir}/${ProductName}.dll"
        Invoke-External $SignTool.FullName verify /pa /v "${ProjectRoot}/release/${OutputName}.exe"
        Log-Group
    }
}

Package
