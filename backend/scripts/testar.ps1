param([Parameter(Mandatory=$true)][string]$DatabaseUrl, [string]$ReportPath)
$ErrorActionPreference = 'Stop'
if ($DatabaseUrl -notmatch '/[^/?]+_test(?:\?.*)?$') {
    throw 'Use um banco descartável terminado em _test.'
}
$variaveisTeste = @('THERMOTRACE_TEST_DATABASE_URL', 'DATABASE_URL', 'SECRET_KEY', 'AMBIENTE')
$ambienteAnteriorTeste = @{}
foreach ($nomeTeste in $variaveisTeste) { $ambienteAnteriorTeste[$nomeTeste] = [Environment]::GetEnvironmentVariable($nomeTeste) }
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    $env:THERMOTRACE_TEST_DATABASE_URL = $DatabaseUrl
    $env:DATABASE_URL = $DatabaseUrl
    $env:SECRET_KEY = 'synthetic-only-test-key-0a1b2c3d4e5f6g7h8i9j'
    $env:AMBIENTE = 'local'
    & .\.venv\Scripts\python.exe -m alembic upgrade head
    if ($LASTEXITCODE -ne 0) { throw 'Migração falhou.' }
    & .\.venv\Scripts\python.exe -m alembic check
    if ($LASTEXITCODE -ne 0) { throw 'Modelos e migrações divergem.' }
    $argumentosTeste = @('-m', 'pytest', '-q')
    if ($ReportPath) { $argumentosTeste += "--junitxml=$ReportPath" }
    & .\.venv\Scripts\python.exe @argumentosTeste
    if ($LASTEXITCODE -ne 0) { throw 'Testes falharam.' }
} finally {
    foreach ($nomeTeste in $variaveisTeste) { [Environment]::SetEnvironmentVariable($nomeTeste, $ambienteAnteriorTeste[$nomeTeste]) }
    Pop-Location
}
