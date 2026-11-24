# OBS Director: let phones on this network reach the plugin through Windows Firewall.
# Run by the installer as administrator when "Allow phones on this network through Windows
# Firewall" is ticked. Safe to run again.

$ErrorActionPreference = 'Continue'

# Local networks only (private address ranges), on any network type Windows picked
# (Private or Public), and only the plugin's ports.
$Local = @('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16', 'LocalSubnet')
$Rules = @(
    @{ Name = 'OBS Director (control and video)'; Protocol = 'TCP'; Ports = @('4466', '4468', '4470')
       Description = 'Lets OBS Director phones and browsers on this network connect to OBS.' },
    @{ Name = 'OBS Director (discovery)'; Protocol = 'UDP'; Ports = @('4467')
       Description = 'Lets OBS Director phones on this network find this PC.' }
)
foreach ($r in $Rules) {
    Get-NetFirewallRule -DisplayName $r.Name -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    New-NetFirewallRule -DisplayName $r.Name -Description $r.Description -Direction Inbound -Action Allow `
        -Protocol $r.Protocol -LocalPort $r.Ports -RemoteAddress $Local -Profile Any | Out-Null
    Write-Output "Added: $($r.Name)"
}

# When Windows asks "allow OBS on this network?" and the answer is Cancel, or only Private is
# ticked while the network is Public, Windows saves a rule that BLOCKS OBS. A block rule beats
# every allow rule, so phones still can't connect. Remove those block rules for OBS only.
$Blocks = Get-NetFirewallApplicationFilter -ErrorAction SilentlyContinue |
    Where-Object { $_.Program -like '*\obs64.exe' } |
    Get-NetFirewallRule -ErrorAction SilentlyContinue |
    Where-Object { $_.Direction -eq 'Inbound' -and $_.Action -eq 'Block' }
foreach ($b in $Blocks) {
    Write-Output "Removed a rule that blocked OBS: $($b.DisplayName) ($($b.Profile))"
    $b | Remove-NetFirewallRule
}
exit 0
