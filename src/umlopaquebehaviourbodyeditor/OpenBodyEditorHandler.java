package umlopaquebehaviourbodyeditor;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.emf.common.command.CompoundCommand;
import org.eclipse.emf.edit.command.SetCommand;
import org.eclipse.emf.edit.domain.AdapterFactoryEditingDomain;
import org.eclipse.emf.edit.domain.EditingDomain;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.uml2.uml.OpaqueBehavior;
import org.eclipse.uml2.uml.UMLPackage;

/**
 * Command handler that opens the {@link OpaqueBehaviorBodyDialog}
 * for the currently selected {@link OpaqueBehavior} element.
 */
public class OpenBodyEditorHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);

        try {
            return doExecute(event, shell);
        } catch (Exception e) {
            MessageDialog.openError(shell, "Body Editor Error",
                    "An error occurred:\n" + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private Object doExecute(ExecutionEvent event, Shell shell) {
        // ---- resolve selection ----
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        if (!(selection instanceof IStructuredSelection structured) || structured.isEmpty()) {
            MessageDialog.openInformation(shell, "Body Editor",
                    "No element selected.");
            return null;
        }

        Object element = structured.getFirstElement();

        // The debug dialog confirmed the type is OpaqueBehaviorImpl
        if (!(element instanceof OpaqueBehavior behavior)) {
            MessageDialog.openWarning(shell, "Body Editor",
                    "Selected element is not an OpaqueBehavior.\n"
                  + "Type: " + element.getClass().getName());
            return null;
        }

        // ---- open dialog ----
        List<String> bodies    = new ArrayList<>(behavior.getBodies());
        List<String> languages = new ArrayList<>(behavior.getLanguages());
        String       name      = behavior.getName();

        OpaqueBehaviorBodyDialog dialog =
                new OpaqueBehaviorBodyDialog(shell, bodies, languages, name);

        if (dialog.open() != Window.OK) {
            return null;
        }

        // ---- apply changes ----
        List<String> newBodies    = dialog.getBodies();
        List<String> newLanguages = dialog.getLanguages();

        EditingDomain domain = AdapterFactoryEditingDomain.getEditingDomainFor(behavior);
        if (domain != null) {
            CompoundCommand cmd = new CompoundCommand("Edit OpaqueBehaviour Body");
            cmd.append(SetCommand.create(domain, behavior,
                    UMLPackage.Literals.OPAQUE_BEHAVIOR__BODY, newBodies));
            cmd.append(SetCommand.create(domain, behavior,
                    UMLPackage.Literals.OPAQUE_BEHAVIOR__LANGUAGE, newLanguages));
            domain.getCommandStack().execute(cmd);
        } else {
            behavior.getBodies().clear();
            behavior.getBodies().addAll(newBodies);
            behavior.getLanguages().clear();
            behavior.getLanguages().addAll(newLanguages);
        }

        return null;
    }
}
