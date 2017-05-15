package ch.imedias.rsccfx.view;

import ch.imedias.rsccfx.ControlledPresenter;
import ch.imedias.rsccfx.RsccApp;
import ch.imedias.rsccfx.ViewController;
import ch.imedias.rsccfx.model.Rscc;
import ch.imedias.rsccfx.model.xml.Supporter;
import ch.imedias.rsccfx.model.xml.SupporterHelper;
import java.util.List;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

/**
 * Defines the behaviour of interactions
 * and initializes the size of the GUI components.
 */
public class RsccRequestPresenter implements ControlledPresenter {
  private static final Logger LOGGER =
      Logger.getLogger(RsccRequestPresenter.class.getName());
  private static final int GRID_MAXIMUM_COLUMNS = 3;
  private final Rscc model;
  private final RsccRequestView view;
  private final HeaderPresenter headerPresenter;
  private final SupporterHelper supporterHelper;
  private ViewController viewParent;
  private PopOverHelper popOverHelper;
  private int buttonSize = 0;
  public static List<Supporter> supporters;


  /**
   * Initializes a new RsccRequestPresenter with the matching view.
   *
   * @param model model with all data.
   * @param view  the view belonging to the presenter.
   */
  public RsccRequestPresenter(Rscc model, RsccRequestView view) {
    this.model = model;
    this.view = view;
    headerPresenter = new HeaderPresenter(model, view.headerView);
    supporterHelper = new SupporterHelper();
    initHeader();
    initSupporterList();
    attachEvents();
    popOverHelper = new PopOverHelper(model, RsccApp.REQUEST_VIEW);
  }

  /**
   * Defines the ViewController to allow changing views.
   */
  public void setViewParent(ViewController viewParent) {
    this.viewParent = viewParent;
  }

  private void attachEvents() {
    view.reloadKeyBtn.setOnAction(
        event -> {
          Thread thread = new Thread(model::refreshKey);
          thread.start();
        }
    );

    // handles TitledPane switching between the two TitledPanes
    view.keyGenerationTitledPane.expandedProperty().addListener(
        (observable, oldValue, newValue) -> {
          if (oldValue != newValue) {
            if (newValue) {
              view.supporterTitledPane.setExpanded(false);
              view.contentBox.getChildren().removeAll(view.supporterInnerBox);
              view.contentBox.getChildren().add(1, view.keyGenerationInnerPane);
            }
          }
        }
    );
    view.supporterTitledPane.expandedProperty().addListener(
        (observable, oldValue, newValue) -> {
          if (oldValue != newValue) {
            if (newValue) {
              view.keyGenerationTitledPane.setExpanded(false);
              view.contentBox.getChildren().removeAll(view.keyGenerationInnerPane);
              view.contentBox.getChildren().add(2, view.supporterInnerBox);
            }
          }
        }
    );

    model.connectionStatusStyleProperty().addListener((observable, oldValue, newValue) -> {
      Platform.runLater(() -> {
        view.statusBox.getStyleClass().clear();
        view.statusBox.getStyleClass().add(newValue);
      });
    });

    model.connectionStatusTextProperty().addListener((observable, oldValue, newValue) -> {
      Platform.runLater(() -> {
        view.statusLbl.textProperty().set(newValue);
      });
    });
  }

  /**
   * Initializes the size of the whole RsccRequestView elements.
   *
   * @param scene must be initialized and displayed before calling this method;
   *              The size of all header elements are based on it.
   * @throws NullPointerException if called before this object is fully initialized.
   */
  public void initSize(Scene scene) {
    // initialize view
    view.supporterDescriptionLbl.prefWidthProperty().bind(scene.widthProperty().divide(3));
    view.supporterInnerPane.prefWidthProperty().bind(scene.widthProperty().divide(3).multiply(2));
    view.reloadKeyBtn.prefHeightProperty().bind(view.generatedKeyFld.heightProperty());

  }

  /**
   * Initializes the functionality of the header, e.g. back button and settings button.
   */
  private void initHeader() {
    // Set all the actions regarding buttons in this method.
    headerPresenter.setBackBtnAction(event -> {
      model.killConnection();
      viewParent.setView(RsccApp.HOME_VIEW);
    });
    headerPresenter.setHelpBtnAction(event ->
        popOverHelper.helpPopOver.show(view.headerView.helpBtn));
    headerPresenter.setSettingsBtnAction(event ->
        popOverHelper.settingsPopOver.show(view.headerView.settingsBtn));

    model.isVncServerProcessRunningProperty().addListener((observable, oldValue, newValue) -> {
      if (newValue) {
        headerPresenter.setSettingsBtnVisibility(false);
      } else {
        headerPresenter.setSettingsBtnVisibility(true);
      }
    });

  }

  /**
   * Calls createSupporterList() and creates a button for every supporter found.
   */
  public void initSupporterList() {
    supporters = supporterHelper.loadSupporters();
    // check if invalid format of XML was found during loading
    if (supporters == null) {
      supporters = supporterHelper.getDefaultSupporters();
      supporterHelper.saveSupporters(supporters);
    }

    supporters.stream().forEachOrdered(this::createNewSupporterBtn);

    createNewSupporterBtn(new Supporter());
  }

  /**
   * Creates new SupporterButton and adds it to the GridPane.
   */
  public void createNewSupporterBtn(Supporter supporter) {

    Button supporterBtn = new Button(supporter.toString());
    supporterBtn.getStyleClass().add("supporterBtn");
    initButtonSize(supporterBtn);
    attachContextMenu(supporterBtn, supporter);

    supporterBtn.setOnAction(event -> {
      // if create new button was pressed
      if ("+".equals(supporter.toString())) {
        createNewSupporterBtn(new Supporter());
      }
      // Open Dialog to modify data
      new SupporterAttributesDialog(supporter);
      // Update data in button name and save to preferences
      supporterBtn.setText(supporter.toString());
      supporterHelper.saveSupporters(supporters);
    });

    int row = buttonSize / GRID_MAXIMUM_COLUMNS;
    int column = buttonSize % GRID_MAXIMUM_COLUMNS;
    view.supporterInnerPane.add(supporterBtn, column, row);
    buttonSize++;
  }

  private void attachContextMenu(Button button, Supporter supporter) {
    // Create ContextMenu
    ContextMenu contextMenu = new ContextMenu();

    MenuItem editMenuItem = new MenuItem("Edit");
    // FIXME: new Supporter() must be changed to the supporter of the button
    editMenuItem.setOnAction(event -> new SupporterAttributesDialog(supporter));


    MenuItem connectMenuItem = new MenuItem("Call");
    connectMenuItem.setOnAction(event -> {
      model.callSupporterDirect(supporter.getAddress(), supporter.getPort());
    });

    // Add MenuItem to ContextMenu
    contextMenu.getItems().addAll(editMenuItem, connectMenuItem);

    // When user right-click on Supporterbutton
    button.setOnContextMenuRequested(event -> contextMenu.show(button, event.getScreenX(),
        event.getScreenY()));
  }

  private void initButtonSize(Button button) {
    GridPane.setVgrow(button, Priority.ALWAYS);
    GridPane.setHgrow(button, Priority.ALWAYS);
    GridPane.setValignment(button, VPos.CENTER);
    GridPane.setHalignment(button, HPos.CENTER);
    GridPane.setMargin(button, new Insets(10));

    button.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
    button.setPadding(new Insets(20));

  }

}
