<#
.SYNOPSIS
  Lance, arrête et interroge le serveur Mue Platform, indépendamment de la
  session qui appelle ce script.

.DESCRIPTION
  Deux raisons d'exister, toutes deux constatées en usage réel.

  1. `NODE_EXTRA_CA_CERTS` doit être dans l'environnement du processus **avant**
     que Bun démarre. Bun initialise son magasin de confiance TLS au lancement ;
     la variable posée dans `.env` est lue trop tard par l'application, et le
     serveur — qui est client TLS de lui-même quand il vérifie ses propres JWKS —
     échoue alors en `UNABLE_TO_VERIFY_LEAF_SIGNATURE`, ce qui fait répondre 500
     à `/mcp` pendant `initialize`.

  2. Un serveur lancé depuis une session d'agent meurt avec elle. Le processus
     est donc détaché ici, et survit à la fermeture de l'appelant.

  Le serveur est démarré depuis la racine du dépôt : c'est de là que Bun charge
  `.env`, qui porte `BETTER_AUTH_SECRET` et le reste de la configuration.

.EXAMPLE
  .\scripts\mue-server.ps1 start
  .\scripts\mue-server.ps1 status
  .\scripts\mue-server.ps1 restart -Build
  .\scripts\mue-server.ps1 stop
#>
[CmdletBinding()]
param(
  [Parameter(Position = 0)]
  [ValidateSet("start", "stop", "restart", "status", "logs")]
  [string]$Action = "status",

  # Reconstruit `apps/platform/dist` avant de démarrer.
  [switch]$Build,

  # Suit le journal au lieu d'en afficher la fin.
  [switch]$Follow
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$Entry = Join-Path $Root "apps\platform\dist\server\main.js"
$LogDir = Join-Path $Root ".logs"
$OutLog = Join-Path $LogDir "server.out.log"
$ErrLog = Join-Path $LogDir "server.err.log"
$PidFile = Join-Path $LogDir "server.pid"

function Read-DotEnv {
  <#
    Les valeurs de `.env` dont le *lancement* a besoin, par opposition à celles
    dont l'application a besoin : celles-ci doivent être dans l'environnement
    avant l'exécution, celles-là peuvent être lues après.
  #>
  $path = Join-Path $Root ".env"
  $values = @{}
  if (-not (Test-Path $path)) { return $values }
  foreach ($line in Get-Content $path) {
    $trimmed = $line.Trim()
    if ($trimmed -eq "" -or $trimmed.StartsWith("#")) { continue }
    $split = $trimmed.IndexOf("=")
    if ($split -lt 1) { continue }
    $name = $trimmed.Substring(0, $split).Trim()
    $value = $trimmed.Substring($split + 1).Trim().Trim('"').Trim("'")
    $values[$name] = $value
  }
  return $values
}

function Get-BaseUrl {
  $env_ = Read-DotEnv
  if ($env_.ContainsKey("BETTER_AUTH_URL") -and $env_["BETTER_AUTH_URL"]) {
    return $env_["BETTER_AUTH_URL"].TrimEnd("/")
  }
  return "https://127.0.0.1:3000"
}

function Get-ServerPid {
  <#
    Le PID enregistré, s'il désigne encore un processus vivant. Un fichier PID
    périmé — après un arrêt brutal, un redémarrage de la machine — désigne au
    mieux rien, au pire un processus qui n'est pas le nôtre : d'où la
    vérification, et le refus de rendre un PID qu'on ne peut pas confirmer.
  #>
  if (-not (Test-Path $PidFile)) { return $null }
  $recorded = (Get-Content $PidFile -Raw).Trim()
  if (-not $recorded) { return $null }
  $process = Get-Process -Id $recorded -ErrorAction SilentlyContinue
  if (-not $process) { return $null }
  if ($process.ProcessName -ne "bun") { return $null }
  return [int]$recorded
}

function Stop-Server {
  $serverPid = Get-ServerPid
  if ($serverPid) {
    Stop-Process -Id $serverPid -Force
    Write-Host "serveur arrete (pid $serverPid)"
  }
  else {
    Write-Host "aucun serveur enregistre en cours d'execution"
  }
  if (Test-Path $PidFile) { Remove-Item $PidFile -Force }
}

function Start-Server {
  if (Get-ServerPid) {
    Write-Host "deja en cours d'execution (pid $(Get-ServerPid)) — utilise restart"
    return
  }

  if ($Build) {
    Write-Host "construction de apps/platform..."
    Push-Location (Join-Path $Root "apps\platform")
    try { bun run build | Select-Object -Last 3 } finally { Pop-Location }
  }

  if (-not (Test-Path $Entry)) {
    throw "$Entry est absent. Lance avec -Build, ou construis avec: cd apps/platform; bun run build"
  }

  if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir | Out-Null }

  # F-03 : la confiance TLS de Bun se fige au demarrage, donc la variable doit
  # etre posee ici et pas laissee au chargement `.env` de l'application.
  $dotenv = Read-DotEnv
  $ca = $dotenv["NODE_EXTRA_CA_CERTS"]
  if (-not $ca) { $ca = Join-Path $Root "certs\mue-dev-ca.crt" }
  if (-not (Test-Path $ca)) {
    throw "Certificat d'autorite introuvable: $ca. Genere-le avec: bun scripts/dev-tls-cert.ts"
  }
  $env:NODE_EXTRA_CA_CERTS = $ca

  $process = Start-Process -FilePath "bun" `
    -ArgumentList "run", "apps/platform/dist/server/main.js" `
    -WorkingDirectory $Root `
    -WindowStyle Hidden `
    -RedirectStandardOutput $OutLog `
    -RedirectStandardError $ErrLog `
    -PassThru

  Set-Content -Path $PidFile -Value $process.Id -Encoding ascii
  Write-Host "serveur demarre (pid $($process.Id))"
  Write-Host "  CA        $ca"
  Write-Host "  journaux  $OutLog"

  $base = Get-BaseUrl
  for ($i = 1; $i -le 30; $i++) {
    Start-Sleep -Seconds 1
    if (-not (Get-Process -Id $process.Id -ErrorAction SilentlyContinue)) {
      Write-Host "le processus s'est arrete pendant le demarrage. Fin du journal d'erreur:"
      if (Test-Path $ErrLog) { Get-Content $ErrLog -Tail 20 }
      if (Test-Path $PidFile) { Remove-Item $PidFile -Force }
      exit 1
    }
    try {
      $response = Invoke-WebRequest -Uri "$base/health/live" -UseBasicParsing -TimeoutSec 3
      if ($response.StatusCode -eq 200) {
        Write-Host "pret apres ${i}s sur $base"
        return
      }
    }
    catch { }
  }
  Write-Host "demarre, mais $base/health/live n'a pas repondu en 30s"
}

function Show-Status {
  $serverPid = Get-ServerPid
  $base = Get-BaseUrl
  if ($serverPid) { Write-Host "processus  en cours (pid $serverPid)" }
  else { Write-Host "processus  arrete" }

  Write-Host "adresse    $base"
  foreach ($path in @("/health/live", "/.well-known/oauth-authorization-server", "/settings/agents")) {
    try {
      $response = Invoke-WebRequest -Uri "$base$path" -UseBasicParsing -TimeoutSec 3
      Write-Host ("  {0,-46} {1}" -f $path, $response.StatusCode)
    }
    catch {
      $code = $_.Exception.Response.StatusCode.value__
      if ($code) { Write-Host ("  {0,-46} {1}" -f $path, $code) }
      else { Write-Host ("  {0,-46} injoignable" -f $path) }
    }
  }
}

switch ($Action) {
  "start" { Start-Server }
  "stop" { Stop-Server }
  "restart" { Stop-Server; Start-Sleep -Seconds 2; Start-Server }
  "status" { Show-Status }
  "logs" {
    if ($Follow) { Get-Content $OutLog, $ErrLog -Tail 20 -Wait }
    else { Get-Content $OutLog, $ErrLog -Tail 40 }
  }
}
