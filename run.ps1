# Liga o servidor do Yoru aberto para a rede (celular) e com recarga automática.
#   .\run.ps1
Set-Location $PSScriptRoot
if (-not (Test-Path .\.venv\Scripts\Activate.ps1)) { Write-Host "Crie o ambiente primeiro: python -m venv .venv; .\.venv\Scripts\Activate.ps1; pip install -r requirements.txt"; exit 1 }
. .\.venv\Scripts\Activate.ps1
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
