package wfcomponent;

import org.eclipse.emf.mwe2.runtime.workflow.IWorkflowComponent;
import org.eclipse.emf.mwe2.runtime.workflow.IWorkflowContext;

import generator.utils.*;

public class Start implements IWorkflowComponent {
	
	static path;
	def String target;
	String createSolicitud;
	String diff;
	
	// True si se está generando el módulo. False si la aplicación.
	public static boolean generatingModule;
	
	@Override
	public void preInvoke() {}

	@Override
	public void invoke(IWorkflowContext ctx) {
        System.setProperty("line.separator", "\n");
        FileUtils.target = target;
		generatingModule = !createSolicitud.equals("true");
		FileUtils.diffPatchActive = diff.equals("true");
		File file = new File(FileUtils.getRoute('INI_DATA')+"paginasMsj.yml");
		File file1 = new File(FileUtils.getRoute('INI_DATA')+"paginasAppMsj.yml");
		if (Start.generatingModule){
			FileUtils.delete(file);
		}
		else{
			FileUtils.delete(file1);
		}
	}

	@Override
	public void postInvoke() {}
}
