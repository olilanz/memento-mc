# Memento Lab - Python Data Analysis Environment

This folder contains a self-contained Python development environment for prototyping algorithms and analyzing Memento world data using Jupyter notebooks.

## Quick Start

### 1. Open in VS Code
```bash
# Open this lab folder in VS Code
code .
```

When prompted, click **"Reopen in Container"** to activate the Python devcontainer.

### 2. Start Jupyter
The Jupyter server runs automatically inside the devcontainer. VS Code's Jupyter extension will connect seamlessly.

### 3. Open Notebooks
- Navigate to `notebooks/` in VS Code
- Click on any `.ipynb` file to open it
- Select the Python kernel when prompted

## Structure

- **`notebooks/`** — Jupyter notebooks for prototyping and analysis
  - `01_data_exploration.ipynb` — Starter notebook with data loading and EDA
- **`data/`** — Dataset folder
  - `memento_world_snapshot.csv` — World snapshot data for analysis
- **`.devcontainer/`** — DevContainer configuration (Python 3.11, Jupyter, dependencies)

## Available Python Libraries

- **Data Processing**: `pandas`, `numpy`
- **Visualization**: `plotly`, `matplotlib`
- **Machine Learning**: `scikit-learn`, `scipy`
- **Notebooks**: `jupyter`, `jupyterlab`, `ipywidgets`

## Workflow

1. Create a new notebook in `notebooks/` or edit an existing one
2. Load data from `../data/memento_world_snapshot.csv` using pandas
3. Prototype algorithms, visualizations, and analyses
4. Run cells interactively to validate and debug

## Notes

- The devcontainer is isolated and independent from the main Memento Fabric project
- All Python dependencies are automatically installed on first startup
- Notebooks are git-tracked; outputs are not committed (see `.gitignore`)
