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
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
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

        // ---- collect model context types and completion words ----
        Set<String> contextTypes = new HashSet<>();
        Set<String> autocompleteWords = new HashSet<>();
        Map<String, Map<String, String>> typeMembers = new HashMap<>();

        if (behavior.getModel() != null) {
            TreeIterator<EObject> it = behavior.getModel().eAllContents();
            while (it.hasNext()) {
                EObject obj = it.next();
                if (obj instanceof org.eclipse.uml2.uml.Type t) {
                    String typeName = t.getName();
                    if (typeName != null && !typeName.isBlank()) {
                        contextTypes.add(typeName);
                        autocompleteWords.add(typeName);
                    }
                }
                
                if (obj instanceof org.eclipse.uml2.uml.Classifier classifier) {
                    String className = classifier.getName();
                    if (className != null && !className.isBlank()) {
                        Map<String, String> members = typeMembers.computeIfAbsent(className, k -> new HashMap<>());
                        for (org.eclipse.uml2.uml.Property p : classifier.getAllAttributes()) {
                            String pName = p.getName();
                            if (pName != null && !pName.isBlank()) {
                                String typeName = p.getType() != null ? p.getType().getName() : "Object";
                                String retType = typeName;
                                if (p.isMultivalued()) {
                                    String col = "Bag";
                                    if (p.isOrdered() && p.isUnique()) col = "OrderedSet";
                                    else if (p.isOrdered() && !p.isUnique()) col = "Sequence";
                                    else if (!p.isOrdered() && p.isUnique()) col = "Set";
                                    retType = col + "<" + typeName + ">";
                                }
                                members.put(pName, retType);
                                String cap = pName.substring(0, 1).toUpperCase() + pName.substring(1);
                                members.put("get" + cap, retType);
                                members.put("set" + cap, "void");
                            }
                        }
                        for (org.eclipse.uml2.uml.Operation op : classifier.getAllOperations()) {
                            String opName = op.getName();
                            if (opName != null && !opName.isBlank()) {
                                String typeName = op.getType() != null ? op.getType().getName() : "void";
                                String retType = typeName;
                                org.eclipse.uml2.uml.Parameter retParam = op.getReturnResult();
                                if (retParam != null && retParam.isMultivalued()) {
                                    String col = "Bag";
                                    if (retParam.isOrdered() && retParam.isUnique()) col = "OrderedSet";
                                    else if (retParam.isOrdered() && !retParam.isUnique()) col = "Sequence";
                                    else if (!retParam.isOrdered() && retParam.isUnique()) col = "Set";
                                    retType = col + "<" + typeName + ">";
                                }
                                members.put(opName, retType);
                            }
                        }
                    }
                }
                
                if (obj instanceof org.eclipse.uml2.uml.Operation op) {
                    String opName = op.getName();
                    if (opName != null && !opName.isBlank()) {
                        autocompleteWords.add(opName);
                    }
                }
                if (obj instanceof org.eclipse.uml2.uml.Property p) {
                    String pName = p.getName();
                    if (pName != null && !pName.isBlank()) {
                        autocompleteWords.add(pName);
                        String cap = pName.substring(0, 1).toUpperCase() + pName.substring(1);
                        autocompleteWords.add("get" + cap);
                        autocompleteWords.add("set" + cap);
                        
                        if (p.getType() != null && p.getOwner() instanceof org.eclipse.uml2.uml.NamedElement owner) {
                            String typeName = p.getType().getName();
                            String ownerName = owner.getName();
                            if (typeName != null && ownerName != null) {
                                autocompleteWords.add("create" + typeName + "_as_" + pName + "_in_" + ownerName);
                            }
                        }
                    }
                }
            }
        }

        OpaqueBehaviorBodyDialog dialog =
                new OpaqueBehaviorBodyDialog(shell, bodies, languages, name, contextTypes, autocompleteWords, typeMembers);

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
