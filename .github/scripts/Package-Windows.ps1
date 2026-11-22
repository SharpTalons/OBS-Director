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

    Log-Group "Building ${OutputName}.exe..."
    $IsccArgs = @(
        "/DAppVersion=${ProductVersion}"
        "/DSourceDir=$((Resolve-Path -Path $SourceDir).Path)"
        "/DOutputDir=$((Resolve-Path -Path "${ProjectRoot}/release").Path)"
        "/DOutputName=${OutputName}"
        "${ProjectRoot}/cmake/windows/resources/installer.iss"
    )
    Invoke-External $Iscc @IsccArgs
    Log-Group
}

Package
