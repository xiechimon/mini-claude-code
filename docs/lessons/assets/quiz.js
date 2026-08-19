document.querySelectorAll("[data-quiz]").forEach((quiz) => {
  const feedback = quiz.querySelector("[data-feedback]");
  const explanation = quiz.dataset.explanation || "";

  quiz.querySelectorAll("button[data-answer]").forEach((button) => {
    button.addEventListener("click", () => {
      quiz.querySelectorAll("button[data-answer]").forEach((candidate) => {
        candidate.dataset.state = "";
      });

      const correct = button.dataset.correct === "true";
      button.dataset.state = correct ? "correct" : "wrong";
      feedback.textContent = correct ? `答对了。${explanation}` : "再想想：Turn 和单次模型调用是不是同一层？";
    });
  });
});
