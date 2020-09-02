package scalafix.interfaces;

import java.util.Optional;

public interface ScalafixEvaluation {
    /**
     * @return boolean true if scalafix has successfully been evaluated on all files configured and no ScalafixError has been found
     */
    boolean isSuccessful();

    ScalafixError[] getErrors();

    Optional<String> getMessageError();

    /**
     * @return for each file we store the scalafix evaluation: If the evaluation is successful,
     * we store the list of patches, diagnostics and the corresponding unified diff,
     * otherwise we store the errors resulted from the evaluation
     * */
    ScalafixFileEvaluation[] getFileEvaluations();

    /**
     * @return Applies all patches for all files
     */
    ScalafixError[] apply();
}
