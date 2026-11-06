const promptBox = document.getElementById("prompt");
const resultBox = document.getElementById("result");
const statusBox = document.getElementById("status");
const buildButton = document.getElementById("build-button");

buildButton.addEventListener("click", buildProject);

async function buildProject() {
    const prompt = promptBox.value.trim();

    if (!prompt) {
        statusBox.textContent = "Enter an instruction first.";
        return;
    }

    buildButton.disabled = true;
    statusBox.textContent = "FreeApp AI is processing your request...";
    resultBox.textContent = "";

    try {
        const response = await fetch("/api/generate", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ prompt })
        });

        const data = await response.json().catch(() => ({}));

        if (!response.ok) {
            throw new Error(data.error || "The AI service returned an error.");
        }

        resultBox.textContent = data.result || "No response was returned.";
        statusBox.textContent = "Done.";
    } catch (error) {
        statusBox.textContent = "Request failed.";
        resultBox.textContent = error.message;
    } finally {
        buildButton.disabled = false;
    }
}
