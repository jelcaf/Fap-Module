
package controllers.popups;

import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import messages.Messages;
import messages.Messages.MessageType;
import models.CEconomico;
import models.CEconomicosManuales;
import models.Criterio;
import models.Evaluacion;
import models.SolicitudGenerica;
import models.TipoEvaluacion;
import models.ValoresCEconomico;

import org.apache.log4j.Logger;

import controllers.fap.AgenteController;
import controllers.fap.FichaEvaluadorController;
import controllers.fap.GenericController;
import controllers.fap.SecureController;

import play.mvc.Before;
import play.mvc.Controller;
import play.mvc.Finally;
import play.mvc.Util;
import play.mvc.With;
import security.Accion;
import security.Secure;
import services.BaremacionService;
import validation.CustomValidation;

@With({SecureController.class, AgenteController.class})
public class PopupCEConomicosEvaluadorController extends Controller{
	
	@Inject
	protected static Secure secure;
	
	private static Logger log = Logger.getLogger("Paginas");
	
	@Finally(only="index")
	public static void end(){
		Messages.deleteFlash();
	}

	public static void index(String accion, Long idEvaluacion, Long idCEconomico, Integer duracion) {
		if (accion == null)
			accion = getAccion();
		
		Evaluacion evaluacion = Evaluacion.findById(idEvaluacion);
		
		CEconomico cEconomico = PopupCEConomicosEvaluadorController.getCEconomico(idEvaluacion, idCEconomico);
		log.info("Visitando página: " + "fap/PCEconomico/PopupCEConomicosEvaluador.html");
		renderTemplate("fap/Baremacion/PopupCEConomicosEvaluador.html", accion, idEvaluacion, evaluacion, idCEconomico, cEconomico, duracion);
	}

	public static void editar(Long idEvaluacion, Long idCEconomico, CEconomico cEconomico, Integer duracion) {
		
		checkAuthenticity();

		CEconomico dbCEconomico = PopupCEConomicosEvaluadorController.getCEconomico(idEvaluacion, idCEconomico);

		if (!Messages.hasErrors()) {
			PopupCEConomicosEvaluadorController.PopupCEConomicosEvaluadorValidateCopy("editar", dbCEconomico, cEconomico, duracion);
		}


		if (!Messages.hasErrors()) {
			log.info("Acción Editar de página: "
					+ "fap/PCEconomico/PopupCEConomicosEvaluador.html"
					+ " , intentada con éxito");
			dbCEconomico.save();
			if (!Messages.hasErrors()) {
				Evaluacion eval = Evaluacion.findById(idEvaluacion);
				BaremacionService.calcularTotales(eval, true, true);
				eval.save();
			}
		} else {
			flash();
			log.info("Acción Editar de página: "
					+ "fap/PCEconomico/PopupCEConomicosEvaluador.html"
					+ " , intentada sin éxito (Problemas de Validación)");
		}

		PopupCEConomicosEvaluadorController.editarRender(idEvaluacion, idCEconomico, duracion);
	}

	@Util
	public static void PopupCEConomicosEvaluadorValidateCopy(String accion, CEconomico dbCEconomico, CEconomico cEconomico, Integer duracion) {
		CustomValidation.clearValidadas();
		CustomValidation.valid("cEconomico", cEconomico);
		for (int i=0; i<=duracion; i++){
			dbCEconomico.valores.get(i).valorEstimado = cEconomico.valores.get(i).valorEstimado != null ? cEconomico.valores.get(i).valorEstimado : 0.0;
		}
	}

	@Util
	public static String getAccion() {

		Map<String, Long> ids = (Map<String, Long>) tags.TagMapStack.top("idParams");
		return secure.getPrimeraAccion("solicitudes", ids, null);

	}

	@Util
	public static void editarRender(Long idEvaluacion, Long idCEconomico, Integer duracion) {
		if (!Messages.hasMessages()) {
			renderJSON(utils.RestResponse.ok("Registro actualizado correctamente"));
			Messages.keep();
			redirect("popups.PopupCEConomicosEvaluadorController.index", "editar", idEvaluacion, idCEconomico, duracion);
		}
		Messages.keep();
		redirect("popups.PopupCEConomicosEvaluadorController.index", "editar", idEvaluacion, idCEconomico, duracion);
	}

	@Util
	public static CEconomico getCEconomico(Long idEvaluacion, Long idCEconomico) {
		CEconomico cEconomico = null;

		if (idEvaluacion == null) {
			if (!Messages.messages(MessageType.FATAL).contains(
					"Falta parámetro idEvaluacion"))
				Messages.fatal("Falta parámetro idEvaluacion");
		}
		if (idCEconomico == null) {
			if (!Messages.messages(MessageType.FATAL).contains(
					"Falta parámetro idCEconomico"))
				Messages.fatal("Falta parámetro idCEconomico");
		}
		if (idEvaluacion != null && idCEconomico != null) {
			cEconomico = CEconomico
					.find("select cEconomico from Evaluacion evaluacion join evaluacion.ceconomicos cEconomico where evaluacion.id=? and cEconomico.id=?",
							idEvaluacion, idCEconomico).first();
			if (cEconomico == null) {
				Messages.fatal("Error al recuperar CEconomico");
			}
		}
		return cEconomico;
	}

	@Before
	static void beforeMethod() {
		renderArgs.put("controllerName", "PopupCEConomicosEvaluadorController");
	}
	
	@Util
	private static void flash(){
		TipoEvaluacion tipoEvaluacion = TipoEvaluacion.all().first();
		String param = "cEconomico";
		for (int i = 0; i < tipoEvaluacion.duracion; i++){
			Messages.setFlash(param + ".valores["+i+"].valorEstimado", params.get(param + ".valores["+i+"].valorEstimado", String.class));
		}
		Messages.setFlash(param + ".comentariosAdministracion", params.get(param + ".comentariosAdministracion", String.class));
		Messages.setFlash(param + ".comentariosSolicitante", params.get(param + ".comentariosSolicitante", String.class));
	}
	
//	@Util
//	public static void calcularValoresAuto(CEconomico cEconomico){
//		for (ValoresCEconomico valor: cEconomico.valores){
//			valor.valorSolicitado = sumarValoresHijosOtro(cEconomico.otros, valor.anio);
//		}
//		cEconomico.save();
//	}
//	
//	@Util
//	private static Double sumarValoresHijosOtro(List<CEconomicosManuales> listaHijosOtro, Integer anio){
//		Double suma=0.0;
//		for (CEconomicosManuales cEconomicoManual: listaHijosOtro){
//			if (!cEconomicoManual.valores.isEmpty())
//				if (cEconomicoManual.valores.get(anio).valorSolicitado != null)
//					suma += cEconomicoManual.valores.get(anio).valorSolicitado;
//			}
//		return suma;
//	}
//	
//	@Util
//	public static void recalcular (Long idEvaluacion){
//		Evaluacion evaluacion = Evaluacion.findById(idEvaluacion);
//		for (CEconomico ceconomico : FichaEvaluadorController.filtroConceptosEconomicos(evaluacion)){
//		    //play.Logger.info("El tipo otro es = " + ceconomico.tipo.tipoOtro);
//		    if (ceconomico.tipo.tipoOtro){
//		    	calcularValoresAuto(ceconomico);
//		    } else {
//		    	for (ValoresCEconomico valor: ceconomico.valores){
//					valor.valorSolicitado = sumarValoresHijosOtro(ceconomico.otros, valor.anio);
//				}
//		    }
//		}
//		evaluacion.save();
//	}
}
		